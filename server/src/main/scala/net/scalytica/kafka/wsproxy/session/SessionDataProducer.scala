package net.scalytica.kafka.wsproxy.session

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success

import net.scalytica.kafka.wsproxy._
import net.scalytica.kafka.wsproxy.codecs.BasicSerdes
import net.scalytica.kafka.wsproxy.codecs.SessionSerde
import net.scalytica.kafka.wsproxy.config.Configuration.AppCfg
import net.scalytica.kafka.wsproxy.logging.WithProxyLogger

import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.pekko.Done
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.adapter._
import org.apache.pekko.kafka.ProducerSettings

private[session] class SessionDataProducer(
    implicit cfg: AppCfg,
    sys: ActorSystem[_]
) extends WithProxyLogger {

  private[this] val kSer = BasicSerdes.StringSerializer
  private[this] val vSer = new SessionSerde().serializer()

  private[this] val kafkaUrl = cfg.kafkaClient.bootstrapHosts.mkString()

  private[this] val sessionStateTopic =
    cfg.sessionHandler.topicName.value

  private[this] lazy val producerProps =
    ProducerSettings(sys.toClassic, Some(kSer), Some(vSer))
      .withBootstrapServers(kafkaUrl)
      .withProperties(cfg.producer.kafkaClientProperties)
      .withProperties(producerMetricsProperties)

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
  def publish(
      session: Session
  )(implicit ec: ExecutionContext): Future[Done] = {
    val record = new ProducerRecord[String, Session](
      sessionStateTopic,
      session.sessionId.value,
      session
    )

    val res = producer.send(record).toScalaFuture

    res.onComplete {
      case Success(rm) =>
        log.debug(
          "Successfully sent session record for session id" +
            s" ${session.sessionId.value} to Kafka. [" +
            s"topic: ${rm.topic()}," +
            s"partition: ${rm.partition()}," +
            s"offset: ${rm.offset()}" +
            "]"
        )
        log.trace(s"Session data written was: $session")

      case Failure(ex) =>
        log.error(
          "Failed to send session record for session id" +
            s" ${session.sessionId.value} to Kafka",
          ex
        )
    }

    res.map(_ => Done)
  }

  def publishRemoval(
      sessionId: SessionId
  )(implicit ec: ExecutionContext): Unit = {
    val record = new ProducerRecord[String, Session](
      sessionStateTopic,
      sessionId.value,
      null // scalastyle:ignore
    )
    producer.send(record).toScalaFuture.onComplete {
      case Success(_) =>
        log.debug(
          s"Successfully sent tombstone for session id ${sessionId.value}" +
            " to Kafka"
        )

      case Failure(ex) =>
        log.error(
          s"Failed to send tombstone for session id ${sessionId.value}" +
            " to Kafka",
          ex
        )
    }
  }

  /** Closes the underlying Kafka producer */
  def close(): Unit = producer.close()

}
