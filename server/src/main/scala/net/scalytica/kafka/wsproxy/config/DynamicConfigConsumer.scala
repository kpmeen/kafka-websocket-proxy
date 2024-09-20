package net.scalytica.kafka.wsproxy.config

import net.scalytica.kafka.wsproxy._
import net.scalytica.kafka.wsproxy.codecs.BasicSerdes
import net.scalytica.kafka.wsproxy.codecs.DynamicCfgSerde
import net.scalytica.kafka.wsproxy.config.Configuration.AppCfg
import net.scalytica.kafka.wsproxy.config.DynamicConfigHandlerProtocol._
import net.scalytica.kafka.wsproxy.logging.WithProxyLogger

import org.apache.kafka.clients.consumer.ConsumerConfig._
import org.apache.kafka.clients.consumer.OffsetResetStrategy.EARLIEST
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.kafka.ConsumerSettings
import org.apache.pekko.kafka.Subscriptions
import org.apache.pekko.kafka.scaladsl.Consumer
import org.apache.pekko.stream.scaladsl.Source

/**
 * Consumer implementation for reading
 * [[net.scalytica.kafka.wsproxy.config.Configuration.DynamicCfg]] messages from
 * Kafka.
 *
 * @param cfg
 *   The [[AppCfg]] to use.
 * @param sys
 *   The typed [[ActorSystem]] to use.
 */
private[config] class DynamicConfigConsumer(
    implicit cfg: AppCfg,
    sys: ActorSystem[_]
) extends WithProxyLogger {

  private[this] val kDes = BasicSerdes.StringDeserializer
  private[this] val vDes = new DynamicCfgSerde().deserializer()

  private[this] val kafkaUrl    = cfg.kafkaClient.bootstrapHosts.mkString()
  private[this] val dynCfgTopic = cfg.dynamicConfigHandler.topicName.value

  private[this] lazy val cid = dynCfgConsumerGroupId

  private[this] lazy val consumerProps = {
    ConsumerSettings(sys, kDes, vDes)
      .withBootstrapServers(kafkaUrl)
      .withProperties(cfg.consumer.kafkaClientProperties)
      .withProperties(consumerMetricsProperties)
      .withGroupId(cid)
      .withClientId(cid)
      .withProperties(
        // Always begin at the start of the topic
        AUTO_OFFSET_RESET_CONFIG  -> EARLIEST.name.toLowerCase,
        ENABLE_AUTO_COMMIT_CONFIG -> "false"
      )
  }

  /**
   * The pekko-stream Source consuming messages from the dynamic config topic.
   */
  lazy val dynamicCfgSource: Source[InternalCommand, Consumer.Control] = {
    val subscription = Subscriptions.topics(Set(dynCfgTopic))

    Consumer.plainSource(consumerProps, subscription).map { cr =>
      Option(cr.value())
        .map { value =>
          log.trace(s"Received UpdateDynamicConfigRecord for key ${cr.key()}")
          UpdateDynamicConfigRecord(cr.key(), value, cr.offset())
        }
        .getOrElse {
          log.trace(s"Received RemoveDynamicConfigRecord for key ${cr.key()}")
          RemoveDynamicConfigRecord(cr.key(), cr.offset())
        }
    }
  }
}
