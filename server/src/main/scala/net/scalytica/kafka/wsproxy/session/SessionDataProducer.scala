package net.scalytica.kafka.wsproxy.session

import akka.Done
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.adapter._
import akka.kafka.ProducerSettings
import com.typesafe.scalalogging.Logger
import net.scalytica.kafka.wsproxy.Configuration.AppCfg
import net.scalytica.kafka.wsproxy._
import net.scalytica.kafka.wsproxy.codecs.Implicits._
import net.scalytica.kafka.wsproxy.codecs.{BasicSerdes, SessionSerde}
import org.apache.kafka.clients.producer.ProducerConfig._
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord}

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
 *
 * @param cfg
 * @param sys
 */
private[session] class SessionDataProducer(
    implicit
    cfg: AppCfg,
    sys: ActorSystem[_]
) {

  private[this] val logger = Logger(getClass)

  private[this] val kSer = BasicSerdes.StringSerializer
  private[this] val vSer = new SessionSerde().serializer()

  private[this] val kafkaUrl = cfg.server.kafkaBootstrapUrls.mkString()

  private[this] val sessionStateTopic = cfg.sessionHandler.sessionStateTopicName

  private[this] val producerProps =
    ProducerSettings(sys.toUntyped, Some(kSer), Some(vSer))
      .withBootstrapServers(kafkaUrl)
      .withProperties(
        // scalastyle:off
        // Enables stream monitoring in confluent control center
        INTERCEPTOR_CLASSES_CONFIG -> ProducerInterceptorClass
        // scalastyle:on
      )
      .withProducerFactory { ps =>
        val props: java.util.Properties =
          cfg.producer.kafkaClientProperties ++ ps.getProperties.asScala.toMap
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
   * @param session Session to write
   * @param ec      The [[ExecutionContext]] to use
   * @return eventually returns [[Done]] when successfully completed
   */
  def publish(session: Session)(implicit ec: ExecutionContext): Future[Done] = {
    val record = new ProducerRecord[String, Session](
      sessionStateTopic,
      session.consumerGroupId,
      session
    )

    val res = producer.send(record).toScalaFuture

    res.onComplete {
      case Success(rm) =>
        logger.debug(
          "Successfully sent session record for consumer group" +
            s" ${session.consumerGroupId} to Kafka. [" +
            s"topic: ${rm.topic()}," +
            s"partition: ${rm.partition()}," +
            s"offset: ${rm.offset()}" +
            "]"
        )

      case Failure(ex) =>
        logger.error(
          "Failed to send session record for consumer group" +
            s" ${session.consumerGroupId} to Kafka",
          ex
        )
    }

    res.map(_ => Done)
  }

  def publishRemoval(groupId: String)(implicit ec: ExecutionContext): Unit = {
    val record = new ProducerRecord[String, Session](
      sessionStateTopic,
      groupId,
      null // scalastyle:ignore
    )
    producer.send(record).toScalaFuture.onComplete {
      case Success(_) =>
        logger.debug(
          s"Successfully sent tombstone for consumer group $groupId to Kafka"
        )

      case Failure(ex) =>
        logger.error(
          s"Failed to send tombstone for consumer group $groupId to Kafka",
          ex
        )
    }
  }

  /** Closes the underlying Kafka producer */
  def close(): Unit = producer.close()

}
