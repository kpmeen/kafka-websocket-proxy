package net.scalytica.kafka.wsproxy.session

import akka.Done
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.adapter._
import akka.kafka.ProducerSettings
import net.scalytica.kafka.wsproxy.Configuration.AppCfg
import net.scalytica.kafka.wsproxy._
import net.scalytica.kafka.wsproxy.codecs.Implicits._
import net.scalytica.kafka.wsproxy.codecs.{BasicSerdes, SessionSerde}
import net.scalytica.kafka.wsproxy.logging.WithProxyLogger
import net.scalytica.kafka.wsproxy.models.WsGroupId
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord}

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
 * @param cfg
 *   @param sys
 */
private[session] class SessionDataProducer(
    implicit cfg: AppCfg,
    sys: ActorSystem[_]
) extends WithProxyLogger {

  private[this] val kSer = BasicSerdes.StringSerializer
  private[this] val vSer = new SessionSerde().serializer()

  private[this] val kafkaUrl = cfg.kafkaClient.bootstrapHosts.mkString()

  private[this] val sessionStateTopic =
    cfg.sessionHandler.sessionStateTopicName.value

  private[this] lazy val producerProps =
    ProducerSettings(sys.toClassic, Some(kSer), Some(vSer))
      .withBootstrapServers(kafkaUrl)
      .withProducerFactory(initialiseProducer)

  private[this] def initialiseProducer(
      ps: ProducerSettings[String, Session]
  )(implicit cfg: AppCfg): KafkaProducer[String, Session] = {
    val props =
      cfg.producer.kafkaClientProperties ++
        ps.getProperties.asScala.toMap ++
        producerMetricsProperties

    logger.trace(s"Using producer configuration:\n${props.mkString("\n")}")

    new KafkaProducer[String, Session](
      props,
      ps.keySerializerOpt.orNull,
      ps.valueSerializerOpt.orNull
    )
  }

  private[this] lazy val producer = producerProps.createKafkaProducer()

  /**
   * Writes the [[Session]] data to the session state topic in Kafka.
   *
   * @param session
   *   Session to write
   * @param ec
   *   The [[ExecutionContext]] to use
   * @return
   *   eventually returns [[Done]] when successfully completed
   */
  def publish(session: Session)(implicit ec: ExecutionContext): Future[Done] = {
    val record = new ProducerRecord[String, Session](
      sessionStateTopic,
      session.consumerGroupId.value,
      session
    )

    val res = producer.send(record).toScalaFuture

    res.onComplete {
      case Success(rm) =>
        logger.debug(
          "Successfully sent session record for consumer group" +
            s" ${session.consumerGroupId.value} to Kafka. [" +
            s"topic: ${rm.topic()}," +
            s"partition: ${rm.partition()}," +
            s"offset: ${rm.offset()}" +
            "]"
        )

      case Failure(ex) =>
        logger.error(
          "Failed to send session record for consumer group" +
            s" ${session.consumerGroupId.value} to Kafka",
          ex
        )
    }

    res.map(_ => Done)
  }

  def publishRemoval(
      groupId: WsGroupId
  )(implicit ec: ExecutionContext): Unit = {
    val record = new ProducerRecord[String, Session](
      sessionStateTopic,
      groupId.value,
      null // scalastyle:ignore
    )
    producer.send(record).toScalaFuture.onComplete {
      case Success(_) =>
        logger.debug(
          s"Successfully sent tombstone for consumer group ${groupId.value}" +
            " to Kafka"
        )

      case Failure(ex) =>
        logger.error(
          s"Failed to send tombstone for consumer group ${groupId.value}" +
            " to Kafka",
          ex
        )
    }
  }

  /** Closes the underlying Kafka producer */
  def close(): Unit = producer.close()

}
