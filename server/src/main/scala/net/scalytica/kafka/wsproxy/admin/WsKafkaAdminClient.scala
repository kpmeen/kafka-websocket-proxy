package net.scalytica.kafka.wsproxy.admin

import com.typesafe.scalalogging.Logger
import net.scalytica.kafka.wsproxy.Configuration.AppCfg
import net.scalytica.kafka.wsproxy._
import net.scalytica.kafka.wsproxy.errors.TopicNotFoundError
import net.scalytica.kafka.wsproxy.models.{BrokerInfo, TopicName}
import org.apache.kafka.clients.admin.AdminClientConfig._
import org.apache.kafka.clients.admin._
import org.apache.kafka.common.KafkaException
import org.apache.kafka.common.config.TopicConfig._
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.util.control.NonFatal

/**
 * Simple wrapper around the Kafka AdminClient to allow for bootstrapping the
 * session state topic.
 *
 * @param cfg the [[AppCfg]] to use
 */
class WsKafkaAdminClient(cfg: AppCfg) {

  private[this] val logger = Logger(getClass)

  // scalastyle:off line.size.limit
  private[this] lazy val admConfig = {
    cfg.adminClient.kafkaClientProperties ++ Map[String, AnyRef](
      BOOTSTRAP_SERVERS_CONFIG  -> cfg.kafkaClient.bootstrapHosts.mkString(),
      CLIENT_ID_CONFIG          -> "kafka-websocket-proxy-admin",
      REQUEST_TIMEOUT_MS_CONFIG -> s"${(10 seconds).toMillis}"
    )
  }
  // scalastyle:on line.size.limit

  private[this] val sessionStateTopicStr =
    cfg.sessionHandler.sessionStateTopicName.value
  private[this] val configuredReplicas =
    cfg.sessionHandler.sessionStateReplicationFactor
  private[this] val maxReplicas = 3
  private[this] val retentionDuration =
    cfg.sessionHandler.sessionStateRetention.toMillis

  private[this] lazy val underlying = AdminClient.create(admConfig)

  /**
   * Checks for the existence of the session state topic
   *
   * @return an [[Option]] containing the name of the session state topic.
   */
  private[admin] def findSessionStateTopic: Option[String] = {
    findTopic(cfg.sessionHandler.sessionStateTopicName)
  }

  /** Method for creating the session state topic. */
  private[admin] def createSessionStateTopic(): Unit = {
    val replFactor = replicationFactor
    val tconf = Map[String, String](
      CLEANUP_POLICY_CONFIG -> CLEANUP_POLICY_COMPACT,
      RETENTION_MS_CONFIG   -> s"$retentionDuration"
    ).asJava
    val topic = new NewTopic(sessionStateTopicStr, 1, replFactor).configs(tconf)

    logger.info("Creating session state topic...")
    underlying.createTopics(Seq(topic).asJava).all().get()
    logger.info("Session state topic created.")
  }

  /**
   * Find the topic with the given [[TopicName]]
   *
   * @param topic the [[TopicName]] to find
   */
  def findTopic(topic: TopicName): Option[String] = {
    logger.debug(s"Trying to find topic ${topic.value}...")
    val t = underlying.listTopics().names().get().asScala.find(_ == topic.value)
    logger.debug(
      s"Topic ${topic.value} was ${if (t.nonEmpty) "" else "not "}found"
    )
    t
  }

  /**
   * Check the given topic exists.
   *
   * @param topic the [[TopicName]] to check
   * @return true if the topic exists, else false
   */
  def topicExists(topic: TopicName): Boolean = findTopic(topic).nonEmpty

  /**
   * Trigger the initialisation of the session state topic. This method will
   * first check if the topic already exists before attempting to create it.
   */
  def initSessionStateTopic(): Unit =
    try {
      findSessionStateTopic match {
        case None    => createSessionStateTopic()
        case Some(_) => logger.info("Session state topic verified.")
      }
    } catch {
      case ex: Throwable =>
        logger.error("Session state topic verification failed.", ex)
        throw ex
    }

  /**
   * Fetches the configured number of partitions for the given Kafka topic.
   *
   * @param topicName the topic name to get partitions for
   * @return returns the number of partitions for the topic. If it is not found,
   *         a [[TopicNotFoundError]] is thrown.
   */
  @throws(classOf[TopicNotFoundError])
  def topicPartitions(topicName: TopicName): Int = {
    try {
      underlying
        .describeTopics(Seq(topicName.value).asJava)
        .all()
        .get()
        .asScala
        .headOption
        .map(_._2.partitions().size())
        .getOrElse(0)
    } catch {
      case ee: java.util.concurrent.ExecutionException =>
        ee.getCause match {
          case _: UnknownTopicOrPartitionException =>
            throw TopicNotFoundError(s"Topic ${topicName.value} was not found")
          case ke: KafkaException =>
            logger.error("Unhandled Kafka Exception", ke)
            throw ke
        }
      case NonFatal(e) =>
        logger.error("Unhandled exception", e)
        throw e
    }
  }

  /**
   * Fetch the cluster information for the configured Kafka cluster.
   *
   * @return a List of [[BrokerInfo]] data.
   */
  def clusterInfo: List[BrokerInfo] = {
    logger.debug("Fetching Kafka cluster information...")
    underlying
      .describeCluster()
      .nodes()
      .get()
      .asScala
      .toList
      .map(n => BrokerInfo(n.id, n.host, n.port, Option(n.rack)))
  }

  /**
   * Calculates the replication factor to use for the session state topic.
   *
   * If the configured replication factor value is higher than the number of
   * brokers available, the replication factor will set to the number of
   * available brokers in the cluster.
   *
   * @return the number of replicas to use for the sesion state topic.
   */
  def replicationFactor: Short = {
    val numNodes = clusterInfo.size
    logger.debug(s"Calculating number of replicas for $sessionStateTopicStr...")
    val numReplicas = if (numNodes >= maxReplicas) maxReplicas else numNodes

    if (configuredReplicas > numReplicas) numReplicas.toShort
    else configuredReplicas
  }

  /** Close the underlying admin client. */
  def close(): Unit = {
    underlying.close()
    logger.debug("Underlying admin client closed.")
  }
}

object WsKafkaAdminClient {

  /**
   * Throws an exception if the given [[TopicName]] is not found.
   *
   * @param topic the [[TopicName]] to find
   */
  @throws(classOf[TopicNotFoundError])
  def failIfTopicNotFound(topic: TopicName)(implicit cfg: AppCfg): Unit = {
    val wsAdminClient = new WsKafkaAdminClient(cfg)
    try {
      if (!wsAdminClient.topicExists(topic))
        throw TopicNotFoundError(s"Topic ${topic.value} does not exist")
    } finally {
      wsAdminClient.close()
    }
  }

}
