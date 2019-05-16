package net.scalytica.kafka.wsproxy.admin

import com.typesafe.scalalogging.Logger
import net.scalytica.kafka.wsproxy.Configuration.AppCfg
import net.scalytica.kafka.wsproxy.errors.TopicNotFoundError
import net.scalytica.kafka.wsproxy.{
  KafkaFutureConverter,
  KafkaFutureVoidConverter,
  _
}
import org.apache.kafka.clients.admin.AdminClientConfig._
import org.apache.kafka.clients.admin._
import org.apache.kafka.common.config.TopicConfig._

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

/**
 * Simple wrapper around the Kafka AdminClient to allow for bootstrapping the
 * session state topic.
 *
 * @param cfg the [[AppCfg]] to use
 */
class WsKafkaAdminClient(cfg: AppCfg) {

  private[this] val logger = Logger(getClass)

  private[this] lazy val admConfig =
    cfg.adminClient.kafkaClientProperties ++ Map[String, AnyRef](
      BOOTSTRAP_SERVERS_CONFIG  -> cfg.server.kafkaBootstrapUrls.mkString(),
      CLIENT_ID_CONFIG          -> "kafka-websocket-proxy-admin",
      REQUEST_TIMEOUT_MS_CONFIG -> s"${(10 seconds).toMillis}"
    )

  private[this] val sessionStateTopic = cfg.sessionHandler.sessionStateTopicName
  private[this] val configuredReplicas =
    cfg.sessionHandler.sessionStateReplicationFactor
  private[this] val maxReplicas = 3
  private[this] val retentionDuration =
    cfg.sessionHandler.sessionStateRetention.toMillis

  private[this] lazy val underlying = AdminClient.create(admConfig)

  private[this] def replicationFactor(
      implicit ec: ExecutionContext
  ): Future[Short] = {
    underlying.describeCluster().nodes().call(_.size()).map { numNodes =>
      val numReplicas = if (numNodes >= maxReplicas) maxReplicas else numNodes
      if (configuredReplicas > numReplicas) numReplicas.toShort
      else configuredReplicas
    }
  }

  private[this] def findSessionStateTopic(
      implicit ec: ExecutionContext
  ): Future[Option[String]] =
    underlying
      .listTopics()
      .names()
      .call(n => Set[String](n.asScala.toSeq: _*))
      .map(_.find(_ == sessionStateTopic))

  private[this] def createTopic()(
      implicit ec: ExecutionContext
  ): Future[Unit] = {
    replicationFactor.flatMap { replFactor =>
      val tconf = Map[String, String](
        CLEANUP_POLICY_CONFIG -> CLEANUP_POLICY_COMPACT,
        RETENTION_MS_CONFIG   -> s"$retentionDuration"
      ).asJava
      val topic = new NewTopic(sessionStateTopic, 1, replFactor).configs(tconf)

      logger.info("Creating session state topic...")
      underlying.createTopics(Seq(topic).asJava).all().callVoid()
    }
  }

  /**
   * Trigger the initialisation of the session state topic. This method will
   * first check if the topic already exists before attempting to create it.
   */
  def initSessionStateTopic()(implicit ec: ExecutionContext): Future[Unit] = {
    findSessionStateTopic
      .flatMap {
        case None    => createTopic()
        case Some(_) => Future.successful(())
      }
      .map(_ => underlying.close())
      .map(_ => logger.info("Session state topic verified."))
      .recover {
        case ex: Throwable =>
          logger.error("Session state topic verification failed.", ex)
          throw ex
      }
  }

  /**
   * Fetches the configured number of partitions for the given Kafka topic.
   *
   * @param topicName the topic name to get partitions for
   * @return returns the number of partitions for the topic. If it is not found,
   *         a [[TopicNotFoundError]] is thrown.
   */
  @throws(classOf[TopicNotFoundError])
  def topicPartitions(topicName: String): Int = {
    logger.debug(s"Fetching topic partitions for $topicName...")
    logger.debug(s"Using configuration: ${cfg.server}")
    underlying
      .describeTopics(Seq(topicName).asJava)
      .all()
      .get()
      .asScala
      .headOption
      .map(_._2.partitions().size())
      .orThrow(TopicNotFoundError(s"Topic $topicName was not found"))
  }

}
