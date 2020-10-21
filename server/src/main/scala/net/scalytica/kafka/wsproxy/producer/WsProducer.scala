package net.scalytica.kafka.wsproxy.producer

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.model.ws.Message
import akka.kafka.scaladsl.Producer
import akka.kafka.{ProducerMessage, ProducerSettings}
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Source}
import io.circe.Decoder
import net.scalytica.kafka.wsproxy.config.Configuration.AppCfg
import net.scalytica.kafka.wsproxy.avro.SchemaTypes.AvroProducerRecord
import net.scalytica.kafka.wsproxy.codecs.WsProxyAvroSerde
import net.scalytica.kafka.wsproxy.errors.{
  AuthenticationError,
  AuthorisationError
}
import net.scalytica.kafka.wsproxy.logging.WithProxyLogger
import net.scalytica.kafka.wsproxy.models._
import net.scalytica.kafka.wsproxy.{mapToProperties, producerMetricsProperties}
import org.apache.kafka.clients.producer.{
  KafkaProducer,
  ProducerConfig,
  ProducerRecord,
  Producer => IProducer
}
import org.apache.kafka.common.errors.{
  AuthenticationException,
  AuthorizationException
}
import org.apache.kafka.common.header.Header
import org.apache.kafka.common.serialization.Serializer

import scala.concurrent.ExecutionContext
import scala.jdk.CollectionConverters._

/** Functions for initialising Kafka producer sinks and flows. */
object WsProducer extends ProducerFlowExtras with WithProxyLogger {

  private[this] val SaslJaasConfig: String = "sasl.jaas.config"

  private[this] val PlainLogin = (u: String, p: String) =>
    "org.apache.kafka.common.security.plain.PlainLoginModule required " +
      s"""username="$u" password="$p";"""

  implicit def seqToSource[Out](s: Seq[Out]): Source[Out, NotUsed] = {
    val it = new scala.collection.immutable.Iterable[Out] {
      override def iterator: Iterator[Out] = s.iterator
    }
    Source(it)
  }

  /** Create producer settings to use for the Kafka producer. */
  private[this] def producerSettings[K, V](
      args: InSocketArgs
  )(
      implicit cfg: AppCfg,
      as: ActorSystem,
      ks: Option[Serializer[K]],
      vs: Option[Serializer[V]]
  ) = {
    val kafkaUrl = cfg.kafkaClient.bootstrapHosts.mkString()

    ProducerSettings(as, ks, vs)
      .withBootstrapServers(kafkaUrl)
      .withProducerFactory(initialiseProducer(args.aclCredentials))
      .withProperty(ProducerConfig.CLIENT_ID_CONFIG, args.clientId.value)
  }

  private[this] def initialiseProducer[K, V](
      aclCredentials: Option[AclCredentials]
  )(ps: ProducerSettings[K, V])(implicit cfg: AppCfg): KafkaProducer[K, V] = {
    val props = {
      val jaasProps = aclCredentials match {
        case Some(c) =>
          Map(SaslJaasConfig -> PlainLogin(c.username, c.password))

        case None =>
          Map(SaslJaasConfig -> "")
      }

      // Strip away the default sasl_jaas_config, since the client needs to
      // use their own credentials for auth.
      val kcp = cfg.producer.kafkaClientProperties - SaslJaasConfig

      kcp ++
        ps.getProperties.asScala.toMap ++
        producerMetricsProperties ++
        jaasProps
    }

    new KafkaProducer[K, V](
      props,
      ps.keySerializerOpt.orNull,
      ps.valueSerializerOpt.orNull
    )
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
   * Converts a [[WsProducerRecord]] into a Kafka [[ProducerRecord]].
   *
   * @param topic
   *   the topic name the record is to be written to.
   * @param msg
   *   the message to send to the Kafka topic.
   * @tparam K
   *   the message key type
   * @tparam V
   *   the message value type
   * @return
   *   an instance of [[ProducerRecord]]
   */
  @throws[IllegalStateException]
  private[this] def asKafkaProducerRecord[K, V](
      topic: TopicName,
      msg: WsProducerRecord[K, V]
  ): ProducerRecord[K, V] = {
    val headers: Iterable[Header] =
      msg.headers.getOrElse(Seq.empty).map(_.asRecordHeader)

    msg match {
      case kvm: ProducerKeyValueRecord[K, V] =>
        new ExtendedProducerRecord[K, V](
          topic.value,
          kvm.key.value,
          kvm.value.value,
          headers.asJava
        )

      case vm: ProducerValueRecord[V] =>
        new ExtendedProducerRecord[K, V](
          topic.value,
          vm.value.value,
          headers.asJava
        )

      case ProducerEmptyMessage =>
        throw new IllegalStateException(
          "EmptyMessage passed through stream pipeline, but should have" +
            " been filtered out."
        )
    }
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
   * @param producerClient
   *   the configured [[IProducer]] to use.
   * @tparam K
   *   the key type of the [[IProducer]]
   * @tparam V
   *   the value type of the [[IProducer]]
   */
  @throws[AuthenticationError]
  @throws[AuthorisationError]
  @throws[Throwable]
  private[this] def checkClient[K, V](
      topic: TopicName,
      producerClient: IProducer[K, V]
  ): Unit =
    try {
      val _ = producerClient.partitionsFor(topic.value)
    } catch {
      case ae: AuthenticationException =>
        producerClient.close()
        throw AuthenticationError(ae.getMessage, Some(ae))

      case ae: AuthorizationException =>
        producerClient.close()
        throw AuthorisationError(ae.getMessage, Some(ae))

      case t: Throwable =>
        producerClient.close()
        logger.error(
          s"Unhandled error fetching topic partitions for topic ${topic.value}",
          t
        )
        throw t
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

    val settings       = producerSettingsWithKey[K, V](args)
    val producerClient = settings.createKafkaProducer()

    checkClient(args.topic, producerClient)

    rateLimitedJsonToWsProducerRecordFlow[K, V](args)
      .map { wpr =>
        val record = asKafkaProducerRecord(args.topic, wpr)
        ProducerMessage.Message(record, wpr)
      }
      .via(Producer.flexiFlow(settings.withProducer(producerClient)))
      .map(r => WsProducerResult.fromProducerResult(r))
      .flatMapConcat(seqToSource)
  }

  def produceAvro[K, V](args: InSocketArgs)(
      implicit cfg: AppCfg,
      as: ActorSystem,
      mat: Materializer,
      serde: WsProxyAvroSerde[AvroProducerRecord]
  ): Flow[Message, WsProducerResult, NotUsed] = {
    implicit val ec: ExecutionContext = as.dispatcher

    val keyType                = args.keyType.getOrElse(Formats.AvroType)
    implicit val keySerializer = keyType.serializer
    val valType                = args.valType
    implicit val valSerializer = valType.serializer

    val settings       = producerSettingsWithKey[keyType.Aux, valType.Aux](args)
    val producerClient = settings.createKafkaProducer()

    logger.trace(s"Using serde $serde")

    checkClient(args.topic, producerClient)

    rateLimitedAvroToWsProducerRecordFlow[keyType.Aux, valType.Aux](
      args = args,
      keyType = keyType,
      valType = valType
    ).map { wpr =>
      val record = asKafkaProducerRecord(args.topic, wpr)
      ProducerMessage.Message(record, wpr)
    }.via(Producer.flexiFlow(settings.withProducer(producerClient)))
      .map(r => WsProducerResult.fromProducerResult(r))
      .flatMapConcat(seqToSource)
  }

}
