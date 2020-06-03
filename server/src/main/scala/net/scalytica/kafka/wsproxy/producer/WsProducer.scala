package net.scalytica.kafka.wsproxy.producer

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.model.ws.Message
import akka.kafka.scaladsl.Producer
import akka.kafka.{ProducerMessage, ProducerSettings}
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Source}
import akka.util.ByteString
import io.circe.Decoder
import net.scalytica.kafka.wsproxy.Configuration.AppCfg
import net.scalytica.kafka.wsproxy.avro.SchemaTypes.AvroProducerRecord
import net.scalytica.kafka.wsproxy.codecs.WsProxyAvroSerde
import net.scalytica.kafka.wsproxy.errors.{
  AuthenticationError,
  AuthorisationError
}
import net.scalytica.kafka.wsproxy.logging.WithProxyLogger
import net.scalytica.kafka.wsproxy.models._
import net.scalytica.kafka.wsproxy.{
  mapToProperties,
  producerMetricsProperties,
  wsMessageToByteStringFlow,
  wsMessageToStringFlow
}
import org.apache.kafka.clients.producer.{
  KafkaProducer,
  ProducerRecord,
  Producer => IProducer
}
import org.apache.kafka.common.errors.{
  AuthenticationException,
  AuthorizationException
}
import org.apache.kafka.common.serialization.Serializer

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext

/**
 * Functions for initialising Kafka producer sinks and flows.
 */
object WsProducer extends WithProxyLogger {

  private[this] val SaslJaasConfig: String = "sasl.jaas.config"

  private[this] val PlainLogin = (u: String, p: String) =>
    "org.apache.kafka.common.security.plain.PlainLoginModule required " +
      s"""username="$u" password="$p";"""

  implicit def seqToSource[Out](s: Seq[Out]): Source[Out, NotUsed] = {
    val it = new scala.collection.immutable.Iterable[Out] {
      override def iterator: Iterator[Out] = s.toIterator
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
  }

  private[this] def initialiseProducer[K, V](
      aclCredentials: Option[AclCredentials]
  )(ps: ProducerSettings[K, V])(implicit cfg: AppCfg): KafkaProducer[K, V] = {
    val props = {
      val jaasProps = aclCredentials
        .map(c => SaslJaasConfig -> PlainLogin(c.username, c.password))
        .toMap

      cfg.producer.kafkaClientProperties ++
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
   * Parses an input message, in the form of a JSON String, into an instance of
   * [[WsProducerRecord]], which will be passed on to Kafka down-stream.
   *
   * @param jsonStr the String containing the JSON formatted message
   * @param keyDec  the JSON decoder to use for the message key
   * @param valDec  the JSON decoder to use for the message value
   * @tparam K      the message key type
   * @tparam V      the message value type
   * @return an instance of [[WsProducerRecord]]
   * @throws Throwable when the underlying parser fails.
   */
  private[this] def parseInput[K, V](
      jsonStr: String
  )(implicit keyDec: Decoder[K], valDec: Decoder[V]): WsProducerRecord[K, V] = {
    import io.circe._
    import io.circe.parser._
    import net.scalytica.kafka.wsproxy.codecs.Decoders._

    if (jsonStr.isEmpty) ProducerEmptyMessage
    else {
      parse(jsonStr) match {
        case Left(ParsingFailure(message, err)) =>
          logger.error(s"Error parsing JSON string:\n$message")
          logger.debug(s"JSON was: $jsonStr")
          throw err

        case Right(json) =>
          json.as[WsProducerRecord[K, V]] match {
            case Left(err)  => throw err
            case Right(wpr) => wpr
          }
      }
    }
  }

  /**
   * Converts a [[WsProducerRecord]] into a Kafka [[ProducerRecord]].
   *
   * @param topic the topic name the record is to be written to.
   * @param msg   the message to send to the Kafka topic.
   * @tparam K    the message key type
   * @tparam V    the message value type
   * @return an instance of [[ProducerRecord]]
   */
  private[this] def asKafkaProducerRecord[K, V](
      topic: TopicName,
      msg: WsProducerRecord[K, V]
  ): ProducerRecord[K, V] =
    msg match {
      case kvm: ProducerKeyValueRecord[K, V] =>
        new ProducerRecord[K, V](topic.value, kvm.key.value, kvm.value.value)

      case vm: ProducerValueRecord[V] =>
        new ProducerRecord[K, V](topic.value, vm.value.value)

      case ProducerEmptyMessage =>
        throw new IllegalStateException(
          "EmptyMessage passed through stream pipeline, but should have" +
            " been filtered out."
        )
    }

  /** Convenience function for logging and throwing an error in a Flow */
  private[this] def logAndEmpty[T](msg: String, t: Throwable)(empty: T): T = {
    logger.error(msg, t)
    empty
  }

  /**
   * Call partitionsFor with the client to validate auth etc. This is a
   * workaround for the following issues identified in alpakka-kafka client:
   *
   * - https://github.com/akka/alpakka-kafka/issues/814
   * - https://github.com/akka/alpakka-kafka/issues/796
   *
   * @param topic the [[TopicName]] to fetch partitions for
   * @param producerClient the configured [[IProducer]] to use.
   * @tparam K the key type of the [[IProducer]]
   * @tparam V the value type of the [[IProducer]]
   */
  private[this] def checkClient[K, V](
      topic: TopicName,
      producerClient: IProducer[K, V]
  ): Unit =
    try {
      val _ = producerClient.partitionsFor(topic.value)
    } catch {
      case ae: AuthenticationException =>
        producerClient.close()
        throw AuthenticationError(ae.getMessage, ae)

      case ae: AuthorizationException =>
        producerClient.close()
        throw AuthorisationError(ae.getMessage, ae)

      case t: Throwable =>
        producerClient.close()
        logger.error(
          s"Unhandled error fetching topic partitions for topic ${topic.value}",
          t
        )
        throw t
    }

  /**
   *
   * @param args input arguments defining the base configs for the producer.
   * @param cfg  the [[AppCfg]] containing application configurations.
   * @param as   actor system to use
   * @param mat  actor materializer to use
   * @param ks   the message key serializer to use
   * @param vs   the message value serializer to use
   * @param kd   the JSON decoder to use for the message key
   * @param vd   the JSON decoder to use for the message value
   * @tparam K   the message key type
   * @tparam V   the message value type
   * @return a [[Flow]] that sends messages to Kafka and passes on the result
   *         down-stream for further processing. For example sending the
   *         metadata to the external web client for it to process locally.
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

    wsMessageToStringFlow
      .recover {
        case t: Exception =>
          logAndEmpty("There was an error processing a JSON message", t)("")
      }
      .map(str => parseInput[K, V](str))
      .recover {
        case t: Exception =>
          logAndEmpty(s"JSON message could not be parsed", t)(
            ProducerEmptyMessage
          )
      }
      .filter(_.nonEmpty)
      .map { wpr =>
        val record = asKafkaProducerRecord(args.topic, wpr)
        ProducerMessage.Message(record, record)
      }
      .via(Producer.flexiFlow(settings.withProducer(producerClient)))
      .map(r => WsProducerResult.fromProducerResults(r))
      .flatMapConcat(seqToSource)
  }

  def produceAvro[K, V](args: InSocketArgs)(
      implicit cfg: AppCfg,
      as: ActorSystem,
      mat: Materializer,
      serde: WsProxyAvroSerde[AvroProducerRecord]
  ): Flow[Message, WsProducerResult, NotUsed] = {
    implicit val ec: ExecutionContext = as.dispatcher

    import net.scalytica.kafka.wsproxy.codecs.BasicSerdes.ByteArrSerializer

    val settings       = producerSettingsWithKey[Array[Byte], Array[Byte]](args)
    val producerClient = settings.createKafkaProducer()

    checkClient(args.topic, producerClient)

    wsMessageToByteStringFlow
      .recover {
        case t: Exception =>
          logAndEmpty("There was an error processing an Avro message", t)(
            ByteString.empty
          )
      }
      .map(bs => serde.deserialize(bs.toArray))
      .recover {
        case t: Exception =>
          logAndEmpty(s"Avro message could not be deserialised", t)(
            AvroProducerRecord.empty
          )
      }
      .filterNot(_.isEmpty)
      .map { wpr =>
        val record =
          asKafkaProducerRecord(args.topic, WsProducerRecord.fromAvro(wpr))
        ProducerMessage.Message(record, record)

      }
      .via(Producer.flexiFlow(settings.withProducer(producerClient)))
      .map(r => WsProducerResult.fromProducerResults(r))
      .flatMapConcat(seqToSource)
  }

}
