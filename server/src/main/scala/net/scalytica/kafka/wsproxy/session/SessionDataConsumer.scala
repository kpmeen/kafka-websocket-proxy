package net.scalytica.kafka.wsproxy.session

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.adapter._
import akka.kafka.scaladsl.Consumer
import akka.kafka.{ConsumerSettings, Subscriptions}
import net.scalytica.kafka.wsproxy.Configuration.AppCfg
import net.scalytica.kafka.wsproxy._
import net.scalytica.kafka.wsproxy.codecs.Implicits._
import net.scalytica.kafka.wsproxy.codecs.{BasicSerdes, SessionSerde}
import net.scalytica.kafka.wsproxy.models.WsGroupId
import org.apache.kafka.clients.consumer.ConsumerConfig.{
  AUTO_OFFSET_RESET_CONFIG,
  ENABLE_AUTO_COMMIT_CONFIG
}
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.consumer.OffsetResetStrategy.EARLIEST

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
) extends WithProxyLogger {

  private[this] val kDes = BasicSerdes.StringDeserializer
  private[this] val vDes = new SessionSerde().deserializer()

  private[this] val kafkaUrl = cfg.kafkaClient.bootstrapHosts.mkString()

  private[this] val cid =
    s"ws-proxy-session-consumer-${cfg.server.serverId.value}"

  private[this] val sessionStateTopic =
    cfg.sessionHandler.sessionStateTopicName.value

  private[this] lazy val consumerProps = {
    ConsumerSettings(sys.toClassic, kDes, vDes)
      .withBootstrapServers(kafkaUrl)
      .withGroupId(cid)
      .withClientId(cid)
      .withProperties(
        AUTO_OFFSET_RESET_CONFIG  -> EARLIEST.name.toLowerCase,
        ENABLE_AUTO_COMMIT_CONFIG -> "false"
      )
      .withConsumerFactory(initialiseConsumer)
  }

  private[this] def initialiseConsumer(
      cs: ConsumerSettings[String, Session]
  )(implicit cfg: AppCfg): KafkaConsumer[String, Session] = {
    val props = cfg.consumer.kafkaClientProperties ++
      cs.getProperties.asScala.toMap ++
      consumerMetricsProperties

    logger.trace(s"Using consumer configuration:\n${props.mkString("\n")}")

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
      .log("Session event", cr => cr.key)
      .map { cr =>
        Option(cr.value)
          .map(v => SessionHandlerProtocol.UpdateSession(WsGroupId(cr.key), v))
          .getOrElse(SessionHandlerProtocol.RemoveSession(WsGroupId(cr.key)))
      }
  }

}
