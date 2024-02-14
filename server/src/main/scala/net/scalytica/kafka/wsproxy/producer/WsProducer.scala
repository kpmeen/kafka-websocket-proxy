package net.scalytica.kafka.wsproxy.producer

import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.model.ws.Message
import org.apache.pekko.kafka.scaladsl.Producer
import org.apache.pekko.kafka.{ProducerMessage, ProducerSettings}
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl._
import io.circe.Decoder
import net.scalytica.kafka.wsproxy.auth.KafkaLoginModules
import net.scalytica.kafka.wsproxy.config.Configuration.AppCfg
import net.scalytica.kafka.wsproxy.errors.{
  AuthenticationError,
  AuthorisationError
}
import net.scalytica.kafka.wsproxy.logging.WithProxyLogger
import net.scalytica.kafka.wsproxy.models._
import net.scalytica.kafka.wsproxy.{producerMetricsProperties, SaslJaasConfig}
import org.apache.kafka.clients.producer.ProducerConfig._
import org.apache.kafka.common.errors.{
  AuthenticationException,
  AuthorizationException
}
import org.apache.kafka.common.serialization.Serializer

import scala.concurrent.ExecutionContext

/** Functions for initialising Kafka producer sinks and flows. */
object WsProducer extends ProducerFlowExtras with WithProxyLogger {

  implicit def seqToSource[Out](s: Seq[Out]): Source[Out, NotUsed] = {
    val it = new scala.collection.immutable.Iterable[Out] {
      override def iterator: Iterator[Out] = s.iterator
    }
    Source(it)
  }

  private[this] def useTransactions(args: InSocketArgs, cfg: AppCfg): Boolean =
    cfg.producer.exactlyOnceEnabled && args.transactional

  /** Create producer settings to use for the Kafka producer. */
  private[this] def producerSettings[K, V](
      args: InSocketArgs
  )(
      implicit cfg: AppCfg,
      as: ActorSystem,
      ks: Option[Serializer[K]],
      vs: Option[Serializer[V]]
  ): ProducerSettings[K, V] = {
    val kafkaUrl = cfg.kafkaClient.bootstrapHosts.mkString()

    val settings = ProducerSettings(as, ks, vs)
      .withCloseProducerOnStop(true)
      .withBootstrapServers(kafkaUrl)
      .withProperty(CLIENT_ID_CONFIG, args.producerId.value)
      .withProperties(completeProducerSettings(args.aclCredentials))

    if (useTransactions(args, cfg)) {
      log.debug("Configuring transactional Kafka producer...")
      // Enabling transactions requires instanceId to be present.
      val transId = FullProducerId(args.producerId, args.instanceId)
      settings
        .withProperty(ENABLE_IDEMPOTENCE_CONFIG, "true")
        .withProperty(ACKS_CONFIG, "all")
        .withProperty(MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, "1")
        .withProperty(TRANSACTIONAL_ID_CONFIG, transId.value)
    } else {
      log.debug("Configuring standard Kafka producer...")
      settings
    }
  }

  private[this] def completeProducerSettings(
      aclCredentials: Option[AclCredentials]
  )(implicit cfg: AppCfg): Map[String, String] = {
    val saslMechanism = cfg.consumer.saslMechanism
    val kafkaLoginModule =
      KafkaLoginModules.fromSaslMechanism(saslMechanism, aclCredentials)
    val jaasProps = KafkaLoginModules.buildJaasProperty(kafkaLoginModule)

    // Strip away the default sasl_jaas_config, since the client needs to
    // use their own credentials for auth.
    val kcp = cfg.producer.kafkaClientProperties - SaslJaasConfig

    kcp ++ producerMetricsProperties ++ jaasProps
  }

  /**
   * Creates an instance of producer settings with key and value serializers.
   */
  private[this] def producerSettingsWithKey[K, V](
      args: InSocketArgs
  )(
      implicit cfg: AppCfg,
      as: ActorSystem,
      ks: Serializer[K],
      vs: Serializer[V]
  ) = producerSettings(args)(cfg, as, Option(ks), Option(vs))

  /**
   * Call partitionsFor with the client to validate auth etc. This is a
   * workaround for the following issues identified in alpakka-kafka client, pre
   * pekko fork:
   *
   *   - https://github.com/akka/alpakka-kafka/issues/814
   *   - https://github.com/akka/alpakka-kafka/issues/796
   *
   * @param topic
   *   the [[TopicName]] to fetch partitions for
   * @param producerSettings
   *   the configured [[ProducerSettings]] to use.
   * @tparam K
   *   the key type of the [[ProducerSettings]]
   * @tparam V
   *   the value type of the [[ProducerSettings]]
   */
  @throws[AuthenticationError]
  @throws[AuthorisationError]
  @throws[Throwable]
  private[this] def checkClient[K, V](
      topic: TopicName,
      producerSettings: ProducerSettings[K, V]
  ): Unit = {
    val client = producerSettings.createKafkaProducer()
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

  private[this] def producerFlow[K, V](
      args: InSocketArgs,
      cfg: AppCfg,
      settings: ProducerSettings[K, V]
  ) = {
    if (!useTransactions(args, cfg)) {
      Producer.flexiFlow[K, V, WsProducerRecord[K, V]](settings)
    } else {
      val transId = FullProducerId(args.producerId, args.instanceId).value
      log.debug(
        s"Initializing transactional producer for transaction.id $transId"
      )
      WsTransactionalProducer
        .flexiFlow[K, V, WsProducerRecord[K, V]](settings, transId)
    }
  }

  /**
   * @param args
   *   input arguments defining the base configs for the producer.
   * @param cfg
   *   the [[AppCfg]] containing application configurations.
   * @param as
   *   actor system to use
   * @param mat
   *   actor materializer to use
   * @param ks
   *   the message key serializer to use
   * @param vs
   *   the message value serializer to use
   * @param kd
   *   the JSON decoder to use for the message key
   * @param vd
   *   the JSON decoder to use for the message value
   * @tparam K
   *   the message key type
   * @tparam V
   *   the message value type
   * @return
   *   a [[Flow]] that sends messages to Kafka and passes on the result
   *   down-stream for further processing. For example sending the metadata to
   *   the external web client for it to process locally.
   */
  def produceJson[K, V](args: InSocketArgs)(
      implicit cfg: AppCfg,
      as: ActorSystem,
      mat: Materializer,
      ks: Serializer[K],
      vs: Serializer[V],
      kd: Decoder[K],
      vd: Decoder[V]
  ): Flow[Message, WsProducerResult, NotUsed] = {
    implicit val ec: ExecutionContext = as.dispatcher

    val settings = producerSettingsWithKey[K, V](args)

    checkClient(args.topic, settings)

    val producer = producerFlow[K, V](args, cfg, settings)

    val pflow = Flow[WsProducerRecord[K, V]]
      .map { r =>
        ProducerMessage.single(
          record = WsProducerRecord.asKafkaProducerRecord(args.topic, r),
          passThrough = r
        )
      }
      .via(producer)
      .map(r => WsProducerResult.fromProducerResult(r))
      .flatMapConcat(seqToSource)

    rateLimitedJsonToWsProducerRecordFlow[K, V](args).via(pflow)
  }

}
