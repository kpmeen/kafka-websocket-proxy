package net.scalytica.kafka.wsproxy.codecs

import java.util.{Map => JMap, UUID}
import io.circe.parser._
import io.circe.{Json, Printer}
import org.apache.kafka.common.serialization.{
  Deserializer,
  Serde,
  Serdes => KSerdes,
  Serializer
}
import org.apache.kafka.common.utils.Bytes

import java.nio.ByteBuffer

/**
 * Definitions of most common primitive serializers and deserializers, plus a
 * couple of custom ones for special scenarios.
 */
object BasicSerdes {

  implicit val EmptySerializer: Serializer[Unit]     = EmptySerde.serializer()
  implicit val EmptyDeserializer: Deserializer[Unit] = EmptySerde.deserializer()

  implicit val StringSerializer: Serializer[String] =
    KSerdes.String().serializer()
  implicit val StringDeserializer: Deserializer[String] =
    KSerdes.String().deserializer()

  implicit val BytesSerializer: Serializer[Bytes] = KSerdes.Bytes().serializer()
  implicit val BytesDeserializer: Deserializer[Bytes] =
    KSerdes.Bytes().deserializer()

  implicit val ByteArrSerializer: Serializer[Array[Byte]] =
    KSerdes.ByteArray().serializer()
  implicit val ByteArrDeserializer: Deserializer[Array[Byte]] =
    KSerdes.ByteArray().deserializer()

  implicit val ByteBufferSerializer: Serializer[ByteBuffer] =
    KSerdes.ByteBuffer().serializer()
  implicit val ByteBufferDeserializer: Deserializer[ByteBuffer] =
    KSerdes.ByteBuffer().deserializer()

  implicit val UuidSerializer: Serializer[UUID] = KSerdes.UUID().serializer()
  implicit val UuidDeserializer: Deserializer[UUID] =
    KSerdes.UUID().deserializer()

  implicit val IntSerializer: Serializer[Int] =
    KSerdes.Integer().serializer().asInstanceOf[Serializer[Int]]

  implicit val IntDeserializer: Deserializer[Int] =
    KSerdes.Integer().deserializer().asInstanceOf[Deserializer[Int]]

  implicit val ShortSerializer: Serializer[Short] =
    KSerdes.Short().serializer().asInstanceOf[Serializer[Short]]

  implicit val ShortDeserializer: Deserializer[Short] =
    KSerdes.Short().deserializer().asInstanceOf[Deserializer[Short]]

  implicit val LongSerializer: Serializer[Long] =
    KSerdes.Long().serializer().asInstanceOf[Serializer[Long]]

  implicit val LongDeserializer: Deserializer[Long] =
    KSerdes.Long().deserializer().asInstanceOf[Deserializer[Long]]

  implicit val DoubleSerializer: Serializer[Double] =
    KSerdes.Double().serializer().asInstanceOf[Serializer[Double]]

  implicit val DoubleDeserializer: Deserializer[Double] =
    KSerdes.Double().deserializer().asInstanceOf[Deserializer[Double]]

  implicit val FloatSerializer: Serializer[Float] =
    KSerdes.Float().serializer().asInstanceOf[Serializer[Float]]

  implicit val FloatDeserializer: Deserializer[Float] =
    KSerdes.Float().deserializer().asInstanceOf[Deserializer[Float]]

  implicit val JsonSerializer: StringBasedSerde[Json] = JsonSerde.serializer()
  implicit val JsonDeserializer: StringBasedSerde[Json] =
    JsonSerde.deserializer()
}

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
