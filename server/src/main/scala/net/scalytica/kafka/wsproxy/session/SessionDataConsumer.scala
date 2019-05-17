package net.scalytica.kafka.wsproxy.session

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.adapter._
import akka.event.Logging
import akka.kafka.scaladsl.Consumer
import akka.kafka.{ConsumerSettings, Subscriptions}
import net.scalytica.kafka.wsproxy.Configuration.AppCfg
import net.scalytica.kafka.wsproxy._
import net.scalytica.kafka.wsproxy.codecs.Implicits._
import net.scalytica.kafka.wsproxy.codecs.{BasicSerdes, SessionSerde}
import net.scalytica.kafka.wsproxy.models.WsGroupId
import org.apache.kafka.clients.consumer.ConsumerConfig.{
  AUTO_OFFSET_RESET_CONFIG,
  ENABLE_AUTO_COMMIT_CONFIG,
  _
}
import org.apache.kafka.clients.consumer.{KafkaConsumer, OffsetResetStrategy}

import scala.collection.JavaConverters._

/**
 * Consumer for processing all updates to session state topic and transforming
 * them into [[SessionHandlerProtocol.Protocol]] messages that can be sent to
 * the [[SessionHandler]]
 *
 * @param cfg the [[AppCfg]] to use
 * @param sys the [[ActorSystem]] to use
 */
private[session] class SessionDataConsumer(
    implicit
    cfg: AppCfg,
    sys: ActorSystem[_]
) {

  implicit private[this] val loggingAdapter =
    Logging(sys.toUntyped, classOf[SessionDataConsumer])

  private[this] val kDes = BasicSerdes.StringDeserializer
  private[this] val vDes = new SessionSerde().deserializer()

  private[this] val kafkaUrl = cfg.server.kafkaBootstrapUrls.mkString()

  private[this] val cid =
    s"ws-proxy-session-consumer-${cfg.server.serverId.value}"

  private[this] val sessionStateTopic =
    cfg.sessionHandler.sessionStateTopicName.value

  private[this] val consumerProps = ConsumerSettings(sys.toUntyped, kDes, vDes)
    .withBootstrapServers(kafkaUrl)
    .withProperties(
      AUTO_OFFSET_RESET_CONFIG -> OffsetResetStrategy.EARLIEST
        .name()
        .toLowerCase,
      ENABLE_AUTO_COMMIT_CONFIG -> "false",
      // Enables stream monitoring in confluent control center
      INTERCEPTOR_CLASSES_CONFIG -> ConsumerInterceptorClass
    )
    .withGroupId(cid)
    .withClientId(cid)
    .withConsumerFactory { cs =>
      val props: java.util.Properties =
        cfg.consumer.kafkaClientProperties ++ cs.getProperties.asScala.toMap
      new KafkaConsumer[String, Session](
        props,
        cs.keyDeserializerOpt.orNull,
        cs.valueDeserializerOpt.orNull
      )
    }

  /**
   * The akka-stream Source consuming messages from the session state topic.
   */
  lazy val sessionStateSource: SessionSource = {
    val subscription = Subscriptions.topics(Set(sessionStateTopic))

    Consumer
      .plainSource(consumerProps, subscription)
      .log("New session record for consumer group", cr => cr.key)
      .map { cr =>
        Option(cr.value)
          .map(v => SessionHandlerProtocol.UpdateSession(WsGroupId(cr.key), v))
          .getOrElse(SessionHandlerProtocol.RemoveSession(WsGroupId(cr.key)))
      }
  }

}
