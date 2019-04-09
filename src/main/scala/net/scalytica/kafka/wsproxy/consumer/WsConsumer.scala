package net.scalytica.kafka.wsproxy.consumer

import akka.actor.ActorSystem
import akka.kafka.ConsumerMessage.CommittableOffset
import akka.kafka.scaladsl.Consumer
import akka.kafka.{ConsumerSettings, Subscriptions}
import akka.stream.scaladsl.Source
import com.typesafe.scalalogging.Logger
import net.scalytica.kafka.wsproxy.Configuration.AppCfg
import net.scalytica.kafka.wsproxy.ConsumerInterceptorClass
import net.scalytica.kafka.wsproxy.models.ValueDetails.OutValueDetails
import net.scalytica.kafka.wsproxy.models._
import org.apache.kafka.clients.consumer.ConsumerConfig._
import org.apache.kafka.clients.consumer.OffsetResetStrategy.EARLIEST
import org.apache.kafka.clients.consumer.{ConsumerRecord, OffsetResetStrategy}
import org.apache.kafka.common.serialization.Deserializer

import scala.concurrent.duration._

/**
 * Functions for initialising Kafka consumer sources.
 */
object WsConsumer {

  private[this] val logger = Logger(getClass)

  /**
   * Instantiates an instance of [[ConsumerSettings]] to be used when creating
   * the Kafka consumer [[Source]].
   */
  private[this] def consumerSettings[K, V](
      id: String,
      gid: Option[String],
      offsetReset: OffsetResetStrategy = EARLIEST,
      autoCommit: Boolean
  )(
      implicit
      cfg: AppCfg,
      as: ActorSystem,
      ks: Deserializer[K],
      vs: Deserializer[V]
  ) = {
    val kafkaUrl = cfg.server.kafkaBootstrapUrls.mkString(",")

    ConsumerSettings(as, ks, vs)
      .withBootstrapServers(kafkaUrl)
      .withProperties(
        // Enables stream monitoring in confluent control center
        INTERCEPTOR_CLASSES_CONFIG     -> ConsumerInterceptorClass,
        AUTO_OFFSET_RESET_CONFIG       -> offsetReset.name.toLowerCase,
        ENABLE_AUTO_COMMIT_CONFIG      -> s"$autoCommit",
        AUTO_COMMIT_INTERVAL_MS_CONFIG -> s"${50.millis.toMillis}"
      )
      .withClientId(s"$id-client")
      .withGroupId(gid.getOrElse(s"$id-group"))
  }

  /** Convenience function for logging a [[ConsumerRecord]]. */
  private[this] def logMessage[K, V](record: ConsumerRecord[K, V]): String = {
    val offset    = record.offset
    val partition = record.partition()
    val ts        = record.timestamp()
    val topic     = record.topic()
    f"topic: $topic%-20s, partition: $partition%-4s, offset: $offset%-12s, " +
      f"timestamp: $ts%-20s"
  }

  /**
   * Creates an akka-streams based Kafka Source for messages where the keys are
   * of type [[K]] and values of type [[V]].
   *
   * Instances of this consumer will automatically commit offsets to Kafka,
   * using the default Kafka consumer commit interval.
   *
   * @param topic    the topic to subscribe to.
   * @param clientId the clientId to give the Kafka consumer
   * @param groupId  the groupId to start a socket for.
   * @param cfg      the [[AppCfg]] containing application configurations.
   * @param as       an implicit ActorSystem
   * @param ks       the Deserializer to use for the message key
   * @param vs       the Deserializer to use for the message value
   * @tparam K the type of the message key
   * @tparam V the type of the message value
   * @return a [[Source]] containing [[WsConsumerRecord]]s.
   */
  def consumeAutoCommit[K, V](
      topic: TopicName,
      clientId: String,
      groupId: Option[String]
  )(
      implicit
      cfg: AppCfg,
      as: ActorSystem,
      ks: Deserializer[K],
      vs: Deserializer[V]
  ): Source[WsConsumerRecord[K, V], Consumer.Control] = {
    logger.debug("Setting up consumer with auto-commit ENABLED")
    val settings =
      consumerSettings[K, V](id = clientId, gid = groupId, autoCommit = true)
    val subscription = Subscriptions.topics(Set(topic.value))

    Consumer
      .plainSource[K, V](settings, subscription)
      .log("Consuming message", msg => logMessage(msg))
      .map(msg => messageTransform(msg, None))
  }

  /**
   * Creates an akka-streams based Kafka Source for messages where the keys are
   * of type [[K]] and values of type [[V]].
   *
   * Instances of this consumer will emit [[WsConsumerRecord]]s that include a
   * reference to the [[CommittableOffset]] for the consumed record. It can then
   * be used to trigger a manual commit in the down-stream processing.
   *
   * @param topic    the topic to subscribe to.
   * @param clientId the clientId to give the Kafka consumer
   * @param groupId  the groupId to start a socket for.
   * @param cfg      the [[AppCfg]] containing application configurations.
   * @param as       an implicit ActorSystem
   * @param ks       the Deserializer to use for the message key
   * @param vs       the Deserializer to use for the message value
   * @tparam K the type of the message key
   * @tparam V the type of the message value
   * @return a [[Source]] containing [[WsConsumerRecord]]s.
   */
  def consumeManualCommit[K, V](
      topic: TopicName,
      clientId: String,
      groupId: Option[String]
  )(
      implicit
      cfg: AppCfg,
      as: ActorSystem,
      ks: Deserializer[K],
      vs: Deserializer[V]
  ): Source[WsConsumerRecord[K, V], Consumer.Control] = {
    logger.debug("Setting up consumer with auto-commit DISABLED")
    val settings =
      consumerSettings[K, V](id = clientId, gid = groupId, autoCommit = false)
    val subscription = Subscriptions.topics(Set(topic.value))

    Consumer
      .committableSource[K, V](settings, subscription)
      .log("Consuming message", msg => logMessage(msg.record))
      .map(msg => messageTransform(msg.record, Option(msg.committableOffset)))
  }

  /**
   * Transforms a [[ConsumerRecord]] into a [[WsConsumerRecord]].
   */
  private[this] def messageTransform[K, V](
      rec: ConsumerRecord[K, V],
      coffset: Option[CommittableOffset]
  ): WsConsumerRecord[K, V] = {
    Option(rec.key)
      .map { k =>
        ConsumerKeyValueRecord[K, V](
          topic = rec.topic,
          partition = rec.partition,
          offset = rec.offset,
          timestamp = rec.timestamp(),
          key = OutValueDetails[K](k),
          value = OutValueDetails[V](rec.value),
          committableOffset = coffset
        )
      }
      .getOrElse {
        ConsumerValueRecord[V](
          topic = rec.topic,
          partition = rec.partition,
          offset = rec.offset,
          timestamp = rec.timestamp(),
          value = OutValueDetails[V](rec.value),
          committableOffset = coffset
        )
      }
  }

}
