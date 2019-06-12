package net.scalytica.kafka.wsproxy.consumer

import akka.actor.ActorSystem
import akka.kafka.ConsumerMessage.CommittableOffset
import akka.kafka.scaladsl.Consumer
import akka.kafka.{ConsumerSettings, Subscriptions}
import akka.stream.scaladsl.Source
import com.typesafe.scalalogging.Logger
import net.scalytica.kafka.wsproxy.Configuration.AppCfg
import net.scalytica.kafka.wsproxy.errors.{
  AuthenticationError,
  AuthorisationError
}
import net.scalytica.kafka.wsproxy.models.ValueDetails.OutValueDetails
import net.scalytica.kafka.wsproxy.models._
import net.scalytica.kafka.wsproxy.{mapToProperties, ConsumerInterceptorClass}
import org.apache.kafka.clients.consumer.ConsumerConfig._
import org.apache.kafka.clients.consumer.{
  ConsumerRecord,
  KafkaConsumer,
  Consumer => IConsumer
}
import org.apache.kafka.common.errors.{
  AuthenticationException,
  AuthorizationException
}
import org.apache.kafka.common.serialization.Deserializer

import scala.collection.JavaConverters._
import scala.concurrent.duration._

/**
 * Functions for initialising Kafka consumer sources.
 */
object WsConsumer {

  private[this] val logger = Logger(getClass)

  // scalastyle:off
  private[this] val SASL_JAAS_CONFIG = "sasl.jaas.config"
  private[this] val PLAIN_LOGIN_MODULE = (uname: String, pass: String) =>
    s"""org.apache.kafka.common.security.plain.PlainLoginModule required username="$uname" password="$pass";"""
  // scalastyle:on

  // scalastyle:off
  /**
   * Instantiates an instance of [[ConsumerSettings]] to be used when creating
   * the Kafka consumer [[Source]].
   */
  private[this] def consumerSettings[K, V](
      args: OutSocketArgs,
      autoCommit: Boolean
  )(
      implicit
      cfg: AppCfg,
      as: ActorSystem,
      kd: Deserializer[K],
      vd: Deserializer[V]
  ) = {
    val kafkaUrl = cfg.kafkaClient.bootstrapHosts.mkString()

    val gid = args.groupId.value

    ConsumerSettings(as, kd, vd)
      .withBootstrapServers(kafkaUrl)
      .withProperties(
        AUTO_OFFSET_RESET_CONFIG       -> args.offsetResetStrategy.name.toLowerCase,
        ENABLE_AUTO_COMMIT_CONFIG      -> s"$autoCommit",
        AUTO_COMMIT_INTERVAL_MS_CONFIG -> s"${50.millis.toMillis}"
      )
      .withClientId(args.clientId.value)
      .withGroupId(gid)
      .withConsumerFactory { cs =>
        val props: java.util.Properties = {
          val metricsProps = if (cfg.kafkaClient.metricsEnabled) {
            // Enables stream monitoring in confluent control center
            Map(INTERCEPTOR_CLASSES_CONFIG -> ConsumerInterceptorClass) ++
              cfg.kafkaClient.confluentMetrics
                .map(cmr => cmr.asPrefixedProperties)
                .getOrElse(Map.empty[String, AnyRef])
          } else {
            Map.empty[String, AnyRef]
          }

          val jaasProps = args.aclCredentials
            .map { c =>
              Map(
                SASL_JAAS_CONFIG -> PLAIN_LOGIN_MODULE(c.username, c.password)
              )
            }
            .getOrElse(Map.empty[String, AnyRef])

          metricsProps ++
            cfg.consumer.kafkaClientProperties ++
            cs.getProperties.asScala.toMap ++
            jaasProps
        }
        new KafkaConsumer[K, V](
          props,
          cs.keyDeserializerOpt.orNull,
          cs.valueDeserializerOpt.orNull
        )
      }
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
   * Call partitionsFor with the client to validate auth etc. This is a
   * workaround for the following issues identified in alpakka-kafka client:
   *
   * - https://github.com/akka/alpakka-kafka/issues/814
   * - https://github.com/akka/alpakka-kafka/issues/796
   *
   * @param topic the [[TopicName]] to fetch partitions for
   * @param consumerClient the configured [[IConsumer]] to use.
   * @tparam K the key type of the [[IConsumer]]
   * @tparam V the value type of the [[IConsumer]]
   */
  private[this] def checkClient[K, V](
      topic: TopicName,
      consumerClient: IConsumer[K, V]
  ): Unit =
    try {
      val _ = consumerClient.partitionsFor(topic.value)
    } catch {
      case ae: AuthenticationException =>
        consumerClient.close()
        throw AuthenticationError(ae.getMessage, ae)

      case ae: AuthorizationException =>
        consumerClient.close()
        throw AuthorisationError(ae.getMessage, ae)

      case t: Throwable =>
        consumerClient.close()
        logger.error(
          s"Unhandled error fetching topic partitions for topic ${topic.value}",
          t
        )
        throw t
    }

  /**
   * Creates an akka-streams based Kafka Source for messages where the keys are
   * of type [[K]] and values of type [[V]].
   *
   * Instances of this consumer will automatically commit offsets to Kafka,
   * using the default Kafka consumer commit interval.
   *
   * @param args     the input arguments to pass on to the consumer
   * @param cfg      the [[AppCfg]] containing application configurations.
   * @param as       an implicit ActorSystem
   * @param kd       the Deserializer to use for the message key
   * @param vd       the Deserializer to use for the message value
   * @tparam K the type of the message key
   * @tparam V the type of the message value
   * @return a [[Source]] containing [[WsConsumerRecord]]s.
   */
  def consumeAutoCommit[K, V](
      args: OutSocketArgs
  )(
      implicit
      cfg: AppCfg,
      as: ActorSystem,
      kd: Deserializer[K],
      vd: Deserializer[V]
  ): Source[WsConsumerRecord[K, V], Consumer.Control] = {
    logger.debug("Setting up consumer with auto-commit ENABLED")
    val settings =
      consumerSettings[K, V](args, autoCommit = true)
    val consumerClient = settings.createKafkaConsumer()

    checkClient(args.topic, consumerClient)

    val subscription = Subscriptions.topics(Set(args.topic.value))

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
   * @param args     the input arguments to pass on to the consumer
   * @param cfg      the [[AppCfg]] containing application configurations.
   * @param as       an implicit ActorSystem
   * @param kd       the Deserializer to use for the message key
   * @param vd       the Deserializer to use for the message value
   * @tparam K the type of the message key
   * @tparam V the type of the message value
   * @return a [[Source]] containing [[WsConsumerRecord]]s.
   */
  def consumeManualCommit[K, V](
      args: OutSocketArgs
  )(
      implicit
      cfg: AppCfg,
      as: ActorSystem,
      kd: Deserializer[K],
      vd: Deserializer[V]
  ): Source[WsConsumerRecord[K, V], Consumer.Control] = {
    logger.debug("Setting up consumer with auto-commit DISABLED")
    val settings =
      consumerSettings[K, V](args, autoCommit = false)
    val consumerClient = settings.createKafkaConsumer()

    checkClient(args.topic, consumerClient)

    val subscription = Subscriptions.topics(Set(args.topic.value))

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
      maybeCommittableOffset: Option[CommittableOffset]
  ): WsConsumerRecord[K, V] = {
    Option(rec.key)
      .map { k =>
        ConsumerKeyValueRecord[K, V](
          topic = TopicName(rec.topic),
          partition = Partition(rec.partition),
          offset = Offset(rec.offset),
          timestamp = Timestamp(rec.timestamp()),
          headers = KafkaHeader.fromKafkaRecordHeaders(rec.headers()),
          key = OutValueDetails[K](k),
          value = OutValueDetails[V](rec.value),
          committableOffset = maybeCommittableOffset
        )
      }
      .getOrElse {
        ConsumerValueRecord[V](
          topic = TopicName(rec.topic),
          partition = Partition(rec.partition),
          offset = Offset(rec.offset),
          timestamp = Timestamp(rec.timestamp()),
          headers = KafkaHeader.fromKafkaRecordHeaders(rec.headers()),
          value = OutValueDetails[V](rec.value),
          committableOffset = maybeCommittableOffset
        )
      }
  }

}
