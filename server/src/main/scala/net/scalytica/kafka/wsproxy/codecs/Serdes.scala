package net.scalytica.kafka.wsproxy.codecs

import java.util.{Map => JMap}

import io.circe.parser._
import io.circe.{Json, Printer}
import net.scalytica.kafka.wsproxy.avro.SchemaTypes.{
  AvroCommit,
  AvroConsumerRecord,
  AvroProducerRecord,
  AvroProducerResult
}
import org.apache.kafka.common.serialization.{
  Deserializer,
  Serde,
  Serdes => KSerdes,
  Serializer
}

/**
 * Definitions of most common primitive serializers and deserializers, plus a
 * couple of custom ones for special scenarios.
 */
object BasicSerdes {

  implicit val EmptySerializer   = EmptySerde.serializer()
  implicit val EmptyDeserializer = EmptySerde.deserializer()

  implicit val StringSerializer   = KSerdes.String().serializer()
  implicit val StringDeserializer = KSerdes.String().deserializer()

  implicit val BytesSerializer   = KSerdes.Bytes().serializer()
  implicit val BytesDeserializer = KSerdes.Bytes().deserializer()

  implicit val ByteArrSerializer   = KSerdes.ByteArray().serializer()
  implicit val ByteArrDeserializer = KSerdes.ByteArray().deserializer()

  implicit val ByteBufferSerializer   = KSerdes.ByteBuffer().serializer()
  implicit val ByteBufferDeserializer = KSerdes.ByteBuffer().deserializer()

  implicit val UuidSerializer   = KSerdes.UUID().serializer()
  implicit val UuidDeserializer = KSerdes.UUID().deserializer()

  implicit val IntSerializer =
    KSerdes.Integer().serializer().asInstanceOf[Serializer[Int]]

  implicit val IntDeserializer =
    KSerdes.Integer().deserializer().asInstanceOf[Deserializer[Int]]

  implicit val ShortSerializer =
    KSerdes.Short().serializer().asInstanceOf[Serializer[Short]]

  implicit val ShortDeserializer =
    KSerdes.Short().deserializer().asInstanceOf[Deserializer[Short]]

  implicit val LongSerializer =
    KSerdes.Long().serializer().asInstanceOf[Serializer[Long]]

  implicit val LongDeserializer =
    KSerdes.Long().deserializer().asInstanceOf[Deserializer[Long]]

  implicit val DoubleSerializer =
    KSerdes.Double().serializer().asInstanceOf[Serializer[Double]]

  implicit val DoubleDeserializer =
    KSerdes.Double().deserializer().asInstanceOf[Deserializer[Double]]

  implicit val FloatSerializer =
    KSerdes.Float().serializer().asInstanceOf[Serializer[Float]]

  implicit val FloatDeserializer =
    KSerdes.Float().deserializer().asInstanceOf[Deserializer[Float]]

  implicit val JsonSerializer   = JsonSerde.serializer()
  implicit val JsonDeserializer = JsonSerde.deserializer()
}

trait ProtocolSerdes {

  implicit val avroProducerRecordSerde: WsProxyAvroSerde[AvroProducerRecord] =
    WsProxyAvroSerde[AvroProducerRecord]()

  implicit val avroProducerResultSerde: WsProxyAvroSerde[AvroProducerResult] =
    WsProxyAvroSerde[AvroProducerResult]()

  implicit val avroConsumerRecordSerde: WsProxyAvroSerde[AvroConsumerRecord] =
    WsProxyAvroSerde[AvroConsumerRecord]()

  implicit val avroCommitSerde: WsProxyAvroSerde[AvroCommit] =
    WsProxyAvroSerde[AvroCommit]()
}

object ProtocolSerdes extends ProtocolSerdes

/**
 * Serde definition for cases where the incoming type is not defined. For
 * example when messages have no key, this serde will be used.
 */
object EmptySerde
    extends Serde[Unit]
    with Serializer[Unit]
    with Deserializer[Unit] {

  override def serializer(): Serializer[Unit] = this

  override def deserializer(): Deserializer[Unit] = this

  override def configure(configs: JMap[String, _], isKey: Boolean): Unit = {}

  override def serialize(topic: String, data: Unit): Array[Byte] =
    Array.emptyByteArray

  override def deserialize(topic: String, data: Array[Byte]): Unit = ()

  override def close(): Unit = {}
}

/**
 * Serde for handling JSON messages. Currently built on top of the String serde.
 */
object JsonSerde extends StringBasedSerde[Json] {

  override def serialize(topic: String, data: Json): Array[Byte] =
    Option(data)
      .map(d => ser.serialize(topic, d.printWith(Printer.noSpaces)))
      .orNull

  override def deserialize(topic: String, data: Array[Byte]): Json = {
    val str = des.deserialize(topic, data)
    parse(str) match {
      case Right(json) => json
      case Left(err)   => throw err.underlying
    }
  }

}
