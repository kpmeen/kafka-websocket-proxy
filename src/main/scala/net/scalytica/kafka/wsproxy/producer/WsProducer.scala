package net.scalytica.kafka.wsproxy.producer

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.model.ws.{BinaryMessage, Message, TextMessage}
import akka.kafka.{ProducerMessage, ProducerSettings}
import akka.kafka.scaladsl.Producer
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Sink}
import akka.util.ByteString
import com.typesafe.scalalogging.Logger
import net.scalytica.kafka.wsproxy.ProducerInterceptorClass
import net.scalytica.kafka.wsproxy.Formats
import net.scalytica.kafka.wsproxy.records._
import org.apache.kafka.clients.producer.ProducerConfig._
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.Serializer

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext

object WsProducer {

  private[this] val logger = Logger(getClass)

  // TODO: Read from config
  private[this] val kafkaUrl = "localhost:29092"

  /**
   *
   * @param as
   * @param ks
   * @param vs
   * @tparam K
   * @tparam V
   * @return
   */
  private[this] def baseProducerSettings[K, V](
      as: ActorSystem,
      ks: Option[Serializer[K]],
      vs: Option[Serializer[V]]
  ) =
    ProducerSettings(as, ks, vs)
      .withBootstrapServers(kafkaUrl)
      .withProperties(
        // Enables stream monitoring in confluent control center
        // scalastyle:off
        INTERCEPTOR_CLASSES_CONFIG -> ProducerInterceptorClass
        // scalastyle:on
      )

  /**
   *
   * @param as
   * @param ks
   * @param vs
   * @tparam K
   * @tparam V
   * @return
   */
  private[this] def producerSettingsWithKey[K, V](
      implicit
      as: ActorSystem,
      ks: Serializer[K],
      vs: Serializer[V]
  ) = baseProducerSettings(as, Option(ks), Option(vs))

  /**
   *
   * @param as
   * @param vs
   * @tparam V
   * @return
   */
  private[this] def producerSettingsNoKey[V](
      implicit
      as: ActorSystem,
      vs: Serializer[V]
  ) = baseProducerSettings(as, None, Some(vs))

  /**
   *
   * @param str
   * @return
   */
  private[this] def stringKeyValueParser(
      str: String
  ): WsProducerRecord[String, String] = {
    // FIXME: This must be changed to parse the String as a JSON representing
    //        a WsProducerRecord type. This will also take care of messages
    //        without any keys.
    str.split("->", 2).map(_.trim).toList match {
      case k :: v :: Nil =>
        ProducerKeyValueRecord(
          key = InValueDetails[String](k, Formats.String),
          value = InValueDetails[String](v, Formats.String)
        )
      case invalid =>
        logger.error(s"Malformed message: $invalid")
        ProducerEmtpyMessage
    }
  }

  /**
   *
   * @param topic
   * @param msg
   * @tparam K
   * @tparam V
   * @return
   */
  private[this] def asProducerRecord[K, V](
      topic: String,
      msg: WsProducerRecord[K, V]
  ): ProducerMessage.Message[K, V, WsProducerRecord[K, V]] = msg match {
    case kvm: ProducerKeyValueRecord[K, V] =>
      ProducerMessage.Message[K, V, WsProducerRecord[K, V]](
        new ProducerRecord[K, V](topic, kvm.key.value, kvm.value.value),
        kvm
      )

    case vm: ProducerValueRecord[V] =>
      ProducerMessage.Message[K, V, WsProducerRecord[K, V]](
        new ProducerRecord[K, V](topic, vm.value.value),
        vm
      )

    case ProducerEmtpyMessage =>
      throw new IllegalStateException(
        "EmptyMessage passed through stream pipeline, but should have" +
          " been filtered out."
      )
  }

  def sink[K, V](topic: String)(
      implicit
      as: ActorSystem,
      mat: ActorMaterializer,
      ks: Serializer[K],
      vs: Serializer[V]
  ): Sink[Message, NotUsed] = {
    implicit val ec: ExecutionContext = as.dispatcher

    Flow[Message]
      .mapAsync(1) {
        case tm: TextMessage    => tm.toStrict(2 seconds).map(_.text)
        case bin: BinaryMessage => bin.toStrict(2 seconds).map(_.data)
      }
      .map {
        case s: String =>
          stringKeyValueParser(s)

        case b: ByteString =>
          ProducerValueRecord[Array[Byte]](
            InValueDetails(b.asByteBuffer.array(), Formats.ByteArray)
          )
      }
      .filter(_.nonEmpty)
      .filterNot(_ == ProducerEmtpyMessage)
      .map {
        case wsm: WsProducerRecord[K, V] =>
          asProducerRecord[K, V](topic, wsm)
      }
      .via(Producer.flexiFlow(producerSettingsWithKey[K, V]))
      .map(_.passThrough)
      .to(Sink.foreach { msg =>
        logger.debug(s"Wrote message $msg to topic $topic")
      })
  }

}
