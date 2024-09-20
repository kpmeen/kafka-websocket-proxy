package net.scalytica.kafka.wsproxy.admin

import java.util.UUID

import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.util.control.NonFatal

import net.scalytica.kafka.wsproxy._
import net.scalytica.kafka.wsproxy.config.Configuration.AppCfg
import net.scalytica.kafka.wsproxy.config.Configuration.InternalStateTopic
import net.scalytica.kafka.wsproxy.config.DynCfgConsumerGroupIdPrefix
import net.scalytica.kafka.wsproxy.errors.KafkaFutureErrorHandler
import net.scalytica.kafka.wsproxy.errors.TopicNotFoundError
import net.scalytica.kafka.wsproxy.logging.WithProxyLogger
import net.scalytica.kafka.wsproxy.models.BrokerInfo
import net.scalytica.kafka.wsproxy.models.ConsumerGroup
import net.scalytica.kafka.wsproxy.models.PartitionOffsetMetadata
import net.scalytica.kafka.wsproxy.models.SimpleTopicDescription
import net.scalytica.kafka.wsproxy.models.TopicName
import net.scalytica.kafka.wsproxy.models.WsGroupId
import net.scalytica.kafka.wsproxy.session.SessionConsumerGroupIdPrefix
import net.scalytica.kafka.wsproxy.utils.BlockingRetry

import org.apache.kafka.clients.admin.AdminClientConfig._
import org.apache.kafka.clients.admin._
import org.apache.kafka.common.KafkaException
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.TopicPartitionInfo
import org.apache.kafka.common.config.TopicConfig._
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException

// scalastyle:off magic.number
/**
 * Simple wrapper around the Kafka AdminClient to allow for bootstrapping the
 * session state topic.
 *
 * @param cfg
 *   the [[AppCfg]] to use
 */
class WsKafkaAdminClient(cfg: AppCfg) extends WithProxyLogger {

  private[this] def admConfig = {
    val uuidSuffix = UUID.randomUUID().toString
    cfg.adminClient.kafkaClientProperties ++ Map[String, String](
      BOOTSTRAP_SERVERS_CONFIG -> cfg.kafkaClient.bootstrapHosts.mkString(),
      CLIENT_ID_CONFIG         -> s"kafka-websocket-proxy-admin-$uuidSuffix"
    )
  }

  private[this] val MaxTopicReplicas = 3

  private[this] lazy val underlying = {
    val cfg = admConfig
    log.debug(s"Init AdminClient with configuration:\n${cfg.mkString("\n")}")
    AdminClient.create(cfg)
  }

  private[admin] def createTopic(
      topicName: TopicName,
      partitions: Short = 1,
      cleanupPolicy: String = CLEANUP_POLICY_DELETE,
      retentionMs: Long = 120000L
  ): Try[Unit] = {
    findTopic(topicName) match {
      case Some(_) =>
        Try(log.info(s"${topicName.value} topic verified."))

      case None =>
        val replFactor = replicationFactor(
          topicName = topicName,
          wantedReplicas = partitions
        )
        val tconf = Map[String, String](
          CLEANUP_POLICY_CONFIG -> cleanupPolicy,
          RETENTION_MS_CONFIG   -> s"$retentionMs"
        ).asJava

        val topic =
          new NewTopic(topicName.value, partitions.toInt, replFactor)
            .configs(tconf)

        log.info(s"Creating topic ${topicName.value}...")
        Try[Unit] {
          val _: Any = underlying.createTopics(Seq(topic).asJava).all().get()
          log.info(
            s"Topic ${topicName.value} created with $partitions partitions."
          )
        }.recover {
          KafkaFutureErrorHandler.handle[Unit] {
            log.info("Topic already exists")
          } { t =>
            log.error(
              s"The topic ${topicName.value} could not " +
                s"be created because: ${t.getMessage}"
            )
          }
        }
    }
  }

  /** Method for creating an internal state topic. */
  private[admin] def createInternalStateTopic(
      ist: InternalStateTopic
  ): Unit = {
    createTopic(
      topicName = ist.topicName,
      cleanupPolicy = CLEANUP_POLICY_COMPACT,
      retentionMs = ist.topicRetention.toMillis
    ) match {
      case Success(_) =>
        log.debug(s"Topic ${ist.topicName.value} validated successfully.")
      case Failure(e) =>
        throw e
    }
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
      )(createInternalStateTopic(cfg)) { _ =>
        log.error(s"${cfg.topicName.value} topic not verified, terminating")
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
  private[admin] def topicPartitionInfoList(
      topicName: TopicName
  ): List[TopicPartitionInfo] = {
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

  def describeTopic(topicName: TopicName): Option[SimpleTopicDescription] = {
    try {
      underlying
        .describeTopics(List(topicName.value).asJava)
        .allTopicNames()
        .get()
        .asScala
        .values
        .headOption
        .filterNot(_.isInternal)
        .map(SimpleTopicDescription.fromKafkaTopicDescription)
    } catch {
      case ee: java.util.concurrent.ExecutionException =>
        ee.getCause match {
          case _: UnknownTopicOrPartitionException => None
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
  private[this] def lastOffsetForSinglePartitionTopic(
      topicName: TopicName
  ): Long = {
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
    lastOffsetForSinglePartitionTopic(cfg.dynamicConfigHandler.topicName)

  /**
   * Fetch the latest offset for the session state topic.
   * @return
   *   a Long indicating the latest offset at the time of calling the function.
   */
  def lastOffsetForSessionStateTopic: Long =
    lastOffsetForSinglePartitionTopic(cfg.sessionHandler.topicName)

  /**
   * Fetch a list of all consumer groups
   *
   * If the flag {{{activeOnly}}} is set to {{{true}}}, only consumer groups
   * that are considered to be active will be listed.
   *
   * @param activeOnly
   *   Flag to indicate if all consumer groups should be returned, or only the *
   *   ones that are defined as active.
   * @param includeInternals
   *   Flag to determine if internal ws-proxy consumer groups for sessions and
   *   config handling should be included in the response.
   * @return
   *   a List of [[ConsumerGroup]]
   *
   * @see
   *   [[ConsumerGroup.isActive]]
   */
  def listConsumerGroups(
      activeOnly: Boolean = false,
      includeInternals: Boolean = false
  ): List[ConsumerGroup] = {
    val defaultOpts = new ListConsumerGroupsOptions()
    val opts =
      if (!activeOnly) defaultOpts
      else defaultOpts.inStates(ConsumerGroup.ActiveStates.asJava)

    val allGroups = underlying
      .listConsumerGroups(opts)
      .all()
      .get()
      .asScala
      .toList
      .map(ConsumerGroup.fromKafkaListing)

    val groups =
      if (includeInternals) allGroups
      else
        allGroups.filterNot(cg =>
          cg.groupId.value.startsWith(DynCfgConsumerGroupIdPrefix) ||
            cg.groupId.value.startsWith(SessionConsumerGroupIdPrefix) ||
            cg.groupId.value.startsWith("_")
        )

    log.trace(
      s"returning consumer groups:${groups.mkString("\n", "\n  - ", "\n")}"
    )
    groups

  }

  /**
   * Fetch detailed description of the given consumer group.
   *
   * @param grpId
   *   The [[WsGroupId]] to describe
   * @return
   *   An option containing [[ConsumerGroup]] information
   */
  def describeConsumerGroup(grpId: WsGroupId): Option[ConsumerGroup] = {
    underlying
      .describeConsumerGroups(Seq(grpId.value).asJava)
      .all()
      .get()
      .asScala
      .values
      .headOption
      .map(ConsumerGroup.fromKafkaDescription)
  }

  /**
   * List all consumer group offsets for the given group id.
   *
   * @param grpId
   *   The [[WsGroupId]] to list offsets for
   * @return
   *   A collection of [[PartitionOffsetMetadata]]
   */
  def listConsumerGroupOffsets(
      grpId: WsGroupId
  ): Seq[PartitionOffsetMetadata] = {
    underlying
      .listConsumerGroupOffsets(grpId.value)
      .all()
      .get()
      .asScala
      .values
      .flatMap(_.asScala)
      .map(PartitionOffsetMetadata.fromKafkaTuple)
      .toSeq
  }

  /**
   * Function for doing a consumer group offset reset for a given group id. If
   * the consumer group doesn't exist, the underlying implementation in the
   * KafkaAdminClient will create a new consumer group with the state "EMPTY".
   *
   * @param grpId
   *   The [[WsGroupId]] to reset offsets for
   * @param offsets
   *   The [[PartitionOffsetMetadata]] information on which topics and
   *   partitions to set a new (reset) offset for
   */
  def alterConsumerGroupOffsets(
      grpId: WsGroupId,
      offsets: List[PartitionOffsetMetadata]
  ): Unit = {
    try {
      val _ = underlying
        .alterConsumerGroupOffsets(
          grpId.value,
          PartitionOffsetMetadata.listToKafkaMap(offsets).asJava
        )
        .all()
        .get()
    } catch {
      case ke: KafkaException =>
        log.error(
          "Kafka threw an exception trying to alter consumer group offsets",
          ke
        )
        throw ke
      case NonFatal(e) =>
        log.error(
          "Unhandled exception trying to alter consumer group offsets",
          e
        )
        throw e
    }
  }

  /**
   * Function that will delete offsets for a given consumer group and topic.
   *
   * @param grpId
   *   The [[WsGroupId]] to delete offsets for
   * @param topic
   *   The [[TopicName]] to delete offsets for
   */
  def deleteConsumerGroupOffsets(grpId: WsGroupId, topic: TopicName): Unit = {
    try {
      val off = listConsumerGroupOffsets(grpId)
        .filter(_.topic == topic)
        .map(_.asKafkaTopicPartition)
        .toSet
        .asJava

      val _ =
        underlying.deleteConsumerGroupOffsets(grpId.value, off).all().get()
    } catch {
      case NonFatal(e) =>
        log.error(
          "Unhandled exception trying to delete consumer group offsets",
          e
        )
        throw e
    }
  }

  /**
   * Fetch the cluster information for the configured Kafka cluster.
   *
   * @return
   *   a List of [[BrokerInfo]] data.
   */
  def clusterInfo: List[BrokerInfo] = {
    log.debug("Fetching Kafka cluster information...")
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
      if (numNodes >= MaxTopicReplicas) MaxTopicReplicas
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
