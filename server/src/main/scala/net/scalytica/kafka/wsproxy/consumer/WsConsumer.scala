package net.scalytica.kafka.wsproxy.consumer

import akka.actor.ActorSystem
import akka.kafka.ConsumerMessage.CommittableOffset
import akka.kafka.scaladsl.Consumer
import akka.kafka.{ConsumerSettings, Subscriptions}
import akka.stream.scaladsl.Source
import net.scalytica.kafka.wsproxy.auth.KafkaLoginModules
import net.scalytica.kafka.wsproxy.config.Configuration.AppCfg
import net.scalytica.kafka.wsproxy.errors.{
  AuthenticationError,
  AuthorisationError
}
import net.scalytica.kafka.wsproxy.logging.WithProxyLogger
import net.scalytica.kafka.wsproxy.models.ValueDetails.OutValueDetails
import net.scalytica.kafka.wsproxy.models._
import net.scalytica.kafka.wsproxy.{
  consumerMetricsProperties,
  mapToProperties,
  SaslJaasConfig
}
import org.apache.kafka.clients.consumer.ConsumerConfig._
import org.apache.kafka.clients.consumer.{ConsumerRecord, KafkaConsumer}
import org.apache.kafka.common.errors.{
  AuthenticationException,
  AuthorizationException
}
import org.apache.kafka.common.serialization.Deserializer

import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

/** Functions for initialising Kafka consumer sources. */
object WsConsumer extends WithProxyLogger {

  /**
   * Instantiates an instance of [[ConsumerSettings]] to be used when creating
   * the Kafka consumer [[Source]].
   */
  private[this] def consumerSettings[K, V](
      args: OutSocketArgs,
      autoCommit: Boolean
  )(
      implicit cfg: AppCfg,
      as: ActorSystem,
      kd: Deserializer[K],
      vd: Deserializer[V]
  ) = {
    val kafkaUrl = cfg.kafkaClient.bootstrapHosts.mkString()

    ConsumerSettings(as, kd, vd)
      .withBootstrapServers(kafkaUrl)
      .withProperties(
        AUTO_OFFSET_RESET_CONFIG       -> args.offsetResetStrategyString,
        ENABLE_AUTO_COMMIT_CONFIG      -> s"$autoCommit",
        AUTO_COMMIT_INTERVAL_MS_CONFIG -> s"${50.millis.toMillis}",
        ISOLATION_LEVEL_CONFIG         -> args.isolationLevel.value
      )
      .withClientId(args.clientId.value)
      .withGroupId(args.groupId.value)
      .withConsumerFactory(initialiseConsumer(args.aclCredentials))
  }

  /**
   * Initialise a new [[KafkaConsumer]] instance
   *
   * @param aclCredentials
   *   Option containing the [[AclCredentials]] to use
   * @param cs
   *   the [[ConsumerSettings]] to apply
   * @param cfg
   *   the [[AppCfg]] to use for configurable parameters
   * @tparam K
   *   the type used for configuring the default key serdes.
   * @tparam V
   *   the type used for configuring the default value serdes.
   * @return
   *   a [[KafkaConsumer]] instance for keys of type [[K]] and value [[V]]
   */
  private[this] def initialiseConsumer[K, V](
      aclCredentials: Option[AclCredentials]
  )(
      cs: ConsumerSettings[K, V]
  )(implicit cfg: AppCfg): KafkaConsumer[K, V] = {
    val props = {
      val saslMechanism = cfg.consumer.saslMechanism
      val kafkaLoginModule =
        KafkaLoginModules.fromSaslMechanism(saslMechanism, aclCredentials)
      val jaasProps = KafkaLoginModules.buildJaasProperty(kafkaLoginModule)
      // Strip away the default sasl_jaas_config, since the client needs to
      // use their own credentials for auth.
      val kcp = cfg.consumer.kafkaClientProperties - SaslJaasConfig

      kcp ++
        cs.getProperties.asScala.toMap ++
        consumerMetricsProperties ++
        jaasProps
    }

    log.trace(
      s"Using consumer configuration: ${props.mkString("\n  ", "\n  ", "")}"
    )

    new KafkaConsumer[K, V](
      props,
      cs.keyDeserializerOpt.orNull,
      cs.valueDeserializerOpt.orNull
    )
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
   *   - https://github.com/akka/alpakka-kafka/issues/814
   *   - https://github.com/akka/alpakka-kafka/issues/796
   *
   * @param topic
   *   the [[TopicName]] to fetch partitions for
   * @param settings
   *   the configured [[ConsumerSettings]] to use.
   * @tparam K
   *   the key type of the [[ConsumerSettings]]
   * @tparam V
   *   the value type of the [[ConsumerSettings]]
   */
  @throws[AuthenticationError]
  @throws[AuthorisationError]
  @throws[Throwable]
  private[this] def checkClient[K, V](
      topic: TopicName,
      settings: ConsumerSettings[K, V]
  ): Unit = {
    val client = settings.createKafkaConsumer()
    try {
      val _ = client.partitionsFor(topic.value)
    } catch {
      case ae: AuthenticationException =>
        client.close()
        throw AuthenticationError(ae.getMessage, Some(ae))

      case ae: AuthorizationException =>
        client.close()
        throw AuthorisationError(ae.getMessage, Some(ae))

      case t: Throwable =>
        client.close()
        log.error(
          s"Unhandled error fetching topic partitions for topic ${topic.value}",
          t
        )
        throw t
    } finally {
      client.close()
    }
  }

  /**
   * Creates an akka-streams based Kafka Source for messages where the keys are
   * of type [[K]] and values of type [[V]].
   *
   * Instances of this consumer will automatically commit offsets to Kafka,
   * using the default Kafka consumer commit interval.
   *
   * @param args
   *   the input arguments to pass on to the consumer
   * @param cfg
   *   the [[AppCfg]] containing application configurations.
   * @param as
   *   an implicit ActorSystem
   * @param kd
   *   the Deserializer to use for the message key
   * @param vd
   *   the Deserializer to use for the message value
   * @tparam K
   *   the type of the message key
   * @tparam V
   *   the type of the message value
   * @return
   *   a [[Source]] containing [[WsConsumerRecord]] s.
   */
  def consumeAutoCommit[K, V](
      args: OutSocketArgs
  )(
      implicit cfg: AppCfg,
      as: ActorSystem,
      kd: Deserializer[K],
      vd: Deserializer[V]
  ): Source[WsConsumerRecord[K, V], Consumer.Control] = {
    log.debug("Setting up consumer with auto-commit ENABLED")
    val settings = consumerSettings[K, V](args, autoCommit = true)

    checkClient(args.topic, settings)

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
   * Instances of this consumer will emit [[WsConsumerRecord]] s that include a
   * reference to the [[CommittableOffset]] for the consumed record. It can then
   * be used to trigger a manual commit in the down-stream processing.
   *
   * @param args
   *   the input arguments to pass on to the consumer
   * @param cfg
   *   the [[AppCfg]] containing application configurations.
   * @param as
   *   an implicit ActorSystem
   * @param kd
   *   the Deserializer to use for the message key
   * @param vd
   *   the Deserializer to use for the message value
   * @tparam K
   *   the type of the message key
   * @tparam V
   *   the type of the message value
   * @return
   *   a [[Source]] containing [[WsConsumerRecord]] s.
   */
  def consumeManualCommit[K, V](
      args: OutSocketArgs
  )(
      implicit cfg: AppCfg,
      as: ActorSystem,
      kd: Deserializer[K],
      vd: Deserializer[V]
  ): Source[WsConsumerRecord[K, V], Consumer.Control] = {
    log.debug("Setting up consumer with auto-commit DISABLED")
    val settings = consumerSettings[K, V](args, autoCommit = false)

    checkClient(args.topic, settings)

    val subscription = Subscriptions.topics(Set(args.topic.value))

    Consumer
      .committableSource[K, V](settings, subscription)
      .log("Consuming message", msg => logMessage(msg.record))
      .map(msg => messageTransform(msg.record, Option(msg.committableOffset)))
  }

  /** Transforms a [[ConsumerRecord]] into a [[WsConsumerRecord]]. */
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
          headers = KafkaHeader.fromKafkaRecordHeaders(rec.headers),
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
          headers = KafkaHeader.fromKafkaRecordHeaders(rec.headers),
          value = OutValueDetails[V](rec.value),
          committableOffset = maybeCommittableOffset
        )
      }
  }

}
