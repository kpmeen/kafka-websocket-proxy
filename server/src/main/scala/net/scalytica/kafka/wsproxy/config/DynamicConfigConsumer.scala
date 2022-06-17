package net.scalytica.kafka.wsproxy.config

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.adapter._
import akka.kafka.scaladsl.Consumer
import akka.kafka.{ConsumerSettings, Subscriptions}
import akka.stream.scaladsl.Source
import net.scalytica.kafka.wsproxy._
import net.scalytica.kafka.wsproxy.codecs.{BasicSerdes, DynamicCfgSerde}
import net.scalytica.kafka.wsproxy.config.Configuration.{AppCfg, DynamicCfg}
import net.scalytica.kafka.wsproxy.config.DynamicConfigHandlerProtocol.{
  InternalCommand,
  RemoveDynamicConfigRecord,
  UpdateDynamicConfigRecord
}
import net.scalytica.kafka.wsproxy.logging.WithProxyLogger
import org.apache.kafka.clients.consumer.ConsumerConfig.{
  AUTO_OFFSET_RESET_CONFIG,
  ENABLE_AUTO_COMMIT_CONFIG
}
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.consumer.OffsetResetStrategy.EARLIEST

import scala.jdk.CollectionConverters._

/**
 * Consumer implementation for reading [[DynamicCfg]] messages from Kafka.
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
    ConsumerSettings(sys.toClassic, kDes, vDes)
      .withBootstrapServers(kafkaUrl)
      .withGroupId(cid)
      .withClientId(cid)
      .withProperties(
        // Always begin at the start of the topic
        AUTO_OFFSET_RESET_CONFIG  -> EARLIEST.name.toLowerCase,
        ENABLE_AUTO_COMMIT_CONFIG -> "false"
      )
      .withConsumerFactory(initialiseConsumer)
  }

  /**
   * Helper function to initialise a Kafka Consumer with the correct settings.
   *
   * @param cs
   *   The [[ConsumerSettings]] to use.
   * @return
   *   an instance of [[KafkaConsumer]]
   */
  private[this] def initialiseConsumer(
      cs: ConsumerSettings[String, DynamicCfg]
  ): KafkaConsumer[String, DynamicCfg] = {
    val props = cfg.consumer.kafkaClientProperties ++
      cs.getProperties.asScala.toMap ++
      consumerMetricsProperties

    log.trace(s"Using consumer configuration:\n${props.mkString("\n")}")

    new KafkaConsumer[String, DynamicCfg](
      props,
      cs.keyDeserializerOpt.orNull,
      cs.valueDeserializerOpt.orNull
    )
  }

  /**
   * The akka-stream Source consuming messages from the dynamic config topic.
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
