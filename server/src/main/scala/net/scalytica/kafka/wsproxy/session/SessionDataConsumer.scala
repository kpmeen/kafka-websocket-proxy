package net.scalytica.kafka.wsproxy.session

import net.scalytica.kafka.wsproxy._
import net.scalytica.kafka.wsproxy.codecs.BasicSerdes
import net.scalytica.kafka.wsproxy.codecs.SessionSerde
import net.scalytica.kafka.wsproxy.config.Configuration.AppCfg
import net.scalytica.kafka.wsproxy.logging.WithProxyLogger

import org.apache.kafka.clients.consumer.ConsumerConfig._
import org.apache.kafka.clients.consumer.OffsetResetStrategy.EARLIEST
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.adapter._
import org.apache.pekko.kafka.ConsumerSettings
import org.apache.pekko.kafka.Subscriptions
import org.apache.pekko.kafka.scaladsl.Consumer

/**
 * Consumer for processing all updates to session state topic and transforming
 * them into [[SessionHandlerProtocol.SessionProtocol]] messages that can be
 * sent to the [[SessionHandler]]
 *
 * @param cfg
 *   the [[AppCfg]] to use
 * @param sys
 *   the [[ActorSystem]] to use
 */
private[session] class SessionDataConsumer(
    implicit cfg: AppCfg,
    sys: ActorSystem[_]
) extends WithProxyLogger {

  private[this] val kDes = BasicSerdes.StringDeserializer
  private[this] val vDes = new SessionSerde().deserializer()

  private[this] val kafkaUrl = cfg.kafkaClient.bootstrapHosts.mkString()

  private[this] lazy val cid = sessionConsumerGroupId

  private[this] val sessionStateTopic =
    cfg.sessionHandler.topicName.value

  private[this] lazy val consumerProps = {
    ConsumerSettings(sys.toClassic, kDes, vDes)
      .withBootstrapServers(kafkaUrl)
      .withGroupId(cid)
      .withClientId(cid)
      .withProperties(
        // Always begin at the start of the topic
        AUTO_OFFSET_RESET_CONFIG  -> EARLIEST.name.toLowerCase,
        ENABLE_AUTO_COMMIT_CONFIG -> "false"
      )
      .withProperties(cfg.consumer.kafkaClientProperties)
      .withProperties(consumerMetricsProperties)
  }

  /**
   * The pekko-stream Source consuming messages from the session state topic.
   */
  lazy val sessionStateSource: SessionSource = {
    val subscription = Subscriptions.topics(Set(sessionStateTopic))

    Consumer.plainSource(consumerProps, subscription).map { cr =>
      Option(cr.value)
        .map { v =>
          log.trace(s"Received UpdateSession event for key ${cr.key()}")
          SessionHandlerProtocol.UpdateSession(
            sessionId = SessionId(cr.key),
            s = v,
            offset = cr.offset()
          )
        }
        .getOrElse {
          log.trace(s"Received RemoveSession event for key ${cr.key()}")
          SessionHandlerProtocol.RemoveSession(SessionId(cr.key), cr.offset())
        }
    }
  }

}
