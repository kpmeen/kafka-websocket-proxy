package net.scalytica.kafka.wsproxy.admin

import net.scalytica.kafka.wsproxy.config.Configuration.{
  AppCfg,
  InternalStateTopic
}
import net.scalytica.kafka.wsproxy._
import net.scalytica.kafka.wsproxy.errors.{
  KafkaFutureErrorHandler,
  TopicNotFoundError
}
import net.scalytica.kafka.wsproxy.logging.WithProxyLogger
import net.scalytica.kafka.wsproxy.models.{BrokerInfo, TopicName}
import net.scalytica.kafka.wsproxy.utils.BlockingRetry
import org.apache.kafka.clients.admin.AdminClientConfig._
import org.apache.kafka.clients.admin._
import org.apache.kafka.common.{
  KafkaException,
  TopicPartition,
  TopicPartitionInfo
}
import org.apache.kafka.common.config.TopicConfig._
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException

import scala.jdk.CollectionConverters._
import scala.concurrent.duration._
import scala.util.Try
import scala.util.control.NonFatal

/**
 * Simple wrapper around the Kafka AdminClient to allow for bootstrapping the
 * session state topic.
 *
 * @param cfg
 *   the [[AppCfg]] to use
 */
class WsKafkaAdminClient(cfg: AppCfg) extends WithProxyLogger {

  // scalastyle:off line.size.limit
  private[this] lazy val admConfig = {
    cfg.adminClient.kafkaClientProperties ++ Map[String, AnyRef](
      BOOTSTRAP_SERVERS_CONFIG -> cfg.kafkaClient.bootstrapHosts.mkString(),
      CLIENT_ID_CONFIG         -> "kafka-websocket-proxy-admin"
    )
  }
  // scalastyle:on line.size.limit

  private[this] val internalTopicMaxReplicas = 3

  private[this] lazy val underlying = AdminClient.create(admConfig)

  /** Method for creating an internal state topic. */
  private[admin] def createInternalStateTopic(ist: InternalStateTopic): Unit = {
    val replFactor = replicationFactor(
      topicName = ist.topicName,
      wantedReplicas = ist.topicReplicationFactor
    )
    val tconf = Map[String, String](
      CLEANUP_POLICY_CONFIG -> CLEANUP_POLICY_COMPACT,
      RETENTION_MS_CONFIG   -> s"${ist.topicRetention.toMillis}"
    ).asJava

    val topic = new NewTopic(ist.topicName.value, 1, replFactor).configs(tconf)

    log.info(s"Creating topic ${ist.topicName.value}...")
    Try {
      underlying.createTopics(Seq(topic).asJava).all().get()
    }.recover {
      KafkaFutureErrorHandler.handle {
        log.info("Topic already exists")
      }(t => log.error(s"Could not create the topic ${ist.topicName.value}", t))
    }.getOrElse(System.exit(1))
    log.info(s"Topic ${ist.topicName.value} created.")
  }

  /**
   * Find the topic with the given [[TopicName]]
   *
   * @param topic
   *   the [[TopicName]] to find
   */
  def findTopic(topic: TopicName): Option[String] = {
    log.debug(s"Trying to find topic ${topic.value}...")
    val t = underlying.listTopics().names().get().asScala.find(_ == topic.value)
    log.debug(
      s"Topic ${topic.value} was ${if (t.nonEmpty) "" else "not "}found"
    )
    t
  }

  /**
   * Check the given topic exists.
   *
   * @param topic
   *   the [[TopicName]] to check
   * @return
   *   true if the topic exists, else false
   */
  def topicExists(topic: TopicName): Boolean = findTopic(topic).nonEmpty

  /**
   * Function for creating an [[InternalStateTopic]] in the Kafka cluster.
   *
   * @param cfg
   *   The [[InternalStateTopic]] config to use when creating the topic.
   */
  private[this] def initInternalTopic(cfg: InternalStateTopic): Unit = {
    try {
      BlockingRetry.retry(
        timeout = cfg.topicInitTimeout,
        interval = cfg.topicInitRetryInterval,
        numRetries = cfg.topicInitRetries
      ) {
        findTopic(cfg.topicName) match {
          case None    => createInternalStateTopic(cfg)
          case Some(_) => log.info(s"${cfg.topicName.value} topic verified.")
        }
      } { _ =>
        log.warn(s"${cfg.topicName.value} topic not verified, terminating")
        System.exit(1)
      }
    } catch {
      case ex: Throwable =>
        log.error(s"${cfg.topicName.value} topic verification failed.", ex)
        throw ex
    }
  }

  /**
   * Trigger the initialisation of the dynamic config topic. This method will
   * first check if the topic already exists before attempting to create it.
   */
  def initDynamicConfigTopic(): Unit =
    initInternalTopic(cfg.dynamicConfigHandler)

  /**
   * Trigger the initialisation of the session state topic. This method will
   * first check if the topic already exists before attempting to create it.
   */
  def initSessionStateTopic(): Unit =
    initInternalTopic(cfg.sessionHandler)

  /**
   * Fetches the configured number of partitions for the given Kafka topic.
   *
   * @param topicName
   *   the topic name to get partitions for
   * @return
   *   returns the number of partitions for the topic. If it is not found, a
   *   [[TopicNotFoundError]] is thrown.
   */
  @throws(classOf[TopicNotFoundError])
  def numTopicPartitions(topicName: TopicName): Int = {
    try {
      val p = underlying
        .describeTopics(Seq(topicName.value).asJava)
        .allTopicNames()
        .get()
        .asScala
        .headOption
        .map(_._2.partitions().size())
        .getOrElse(0)

      log.debug(s"Topic ${topicName.value} has $p partitions")
      p
    } catch {
      case ee: java.util.concurrent.ExecutionException =>
        ee.getCause match {
          case _: UnknownTopicOrPartitionException =>
            throw TopicNotFoundError(s"Topic ${topicName.value} was not found")
          case ke: KafkaException =>
            log.error("Unhandled Kafka Exception", ke)
            throw ke
        }
      case NonFatal(e) =>
        log.error("Unhandled exception", e)
        throw e
    }
  }

  /**
   * Fetches the TopicPartitionInfo for the given Kafka topic partitions.
   *
   * @param topicName
   *   the topic name to get partitions for
   * @return
   *   returns a list of TopicPartitionInfo for the topic. If it is not found, a
   *   [[TopicNotFoundError]] is thrown.
   */
  @throws(classOf[TopicNotFoundError])
  def topicPartitionInfoList(topicName: TopicName): List[TopicPartitionInfo] = {
    try {
      val p = underlying
        .describeTopics(Seq(topicName.value).asJava)
        .allTopicNames()
        .get()
        .asScala
        .headOption
        .map(_._2.partitions().asScala.toList)
        .getOrElse(List.empty)

      log.debug(s"Topic ${topicName.value} has $p partitions")
      p
    } catch {
      case ee: java.util.concurrent.ExecutionException =>
        ee.getCause match {
          case _: UnknownTopicOrPartitionException =>
            throw TopicNotFoundError(s"Topic ${topicName.value} was not found")
          case ke: KafkaException =>
            log.error("Unhandled Kafka Exception", ke)
            throw ke
        }
      case NonFatal(e) =>
        log.error("Unhandled exception", e)
        throw e
    }
  }

  /**
   * Fetch the latest offset for the given topic.
   * @return
   *   a Long indicating the latest offset at the time of calling the function.
   */
  def lastOffsetFor(topicName: TopicName): Long = {
    topicPartitionInfoList(topicName).headOption
      .flatMap { tpi =>
        val partition = new TopicPartition(topicName.value, tpi.partition())
        val query     = Map(partition -> OffsetSpec.latest())
        underlying
          .listOffsets(query.asJava)
          .all()
          .get()
          .asScala
          .headOption
          .map(_._2.offset())
      }
      .getOrElse(0L)
  }

  /**
   * Fetch the latest offset for the dynamic config topic.
   * @return
   *   a Long indicating the latest offset at the time of calling the function.
   */
  def lastOffsetForDynamicConfigTopic: Long =
    lastOffsetFor(cfg.dynamicConfigHandler.topicName)

  /**
   * Fetch the latest offset for the session state topic.
   * @return
   *   a Long indicating the latest offset at the time of calling the function.
   */
  def lastOffsetForSessionStateTopic: Long =
    lastOffsetFor(cfg.sessionHandler.topicName)

  /**
   * Fetch the cluster information for the configured Kafka cluster.
   *
   * @return
   *   a List of [[BrokerInfo]] data.
   */
  def clusterInfo: List[BrokerInfo] = {
    log.trace("Fetching Kafka cluster information...")
    underlying
      .describeCluster()
      .nodes()
      .get()
      .asScala
      .toList
      .map(n => BrokerInfo(n.id, n.host, n.port, Option(n.rack)))
  }

  /**
   * Calculates the replication factor to use for the state topic.
   *
   * If the configured replication factor value is higher than the number of
   * brokers available, the replication factor will set to the number of
   * available brokers in the cluster.
   *
   * @return
   *   the number of replicas to use for the state topic.
   */
  def replicationFactor(topicName: TopicName, wantedReplicas: Short): Short = {
    val numNodes = clusterInfo.size
    log.info(s"Calculating number of replicas for ${topicName.value}...")
    val numReplicas =
      if (numNodes >= internalTopicMaxReplicas) internalTopicMaxReplicas
      else numNodes

    if (wantedReplicas > numReplicas) numReplicas.toShort
    else wantedReplicas
  }

  /**
   * Verify that cluster is ready
   *
   * @return
   *   true if the cluster is ready, else false
   */
  def clusterReady: Boolean = {
    log.info("Verifying that cluster is ready...")
    val timeout  = 5 minutes
    val interval = 10 seconds
    val retries  = 30
    BlockingRetry.retry(
      timeout = timeout,
      interval = interval,
      numRetries = retries
    )(clusterInfo.nonEmpty)(_ => false)
  }

  /** Close the underlying admin client. */
  def close(): Unit = {
    underlying.close()
    log.debug("Underlying admin client closed.")
  }
}
