package net.scalytica.kafka.wsproxy.serdes

import org.apache.kafka.common.serialization.Serdes

object Common {

  implicit val StringSerializer     = Serdes.String().serializer()
  implicit val IntSerializer        = Serdes.Integer().serializer()
  implicit val ShortSerializer      = Serdes.Short().serializer()
  implicit val LongSerializer       = Serdes.Long().serializer()
  implicit val DoubleSerializer     = Serdes.Double().serializer()
  implicit val FloatSerializer      = Serdes.Float().serializer()
  implicit val BytesSerializer      = Serdes.Bytes().serializer()
  implicit val UuidSerializer       = Serdes.UUID().serializer()
  implicit val ByteBufferSerializer = Serdes.ByteBuffer().serializer()
  implicit val ByteArrSerializer    = Serdes.ByteArray().serializer()

  implicit val StringDeserializer     = Serdes.String().deserializer()
  implicit val IntDeserializer        = Serdes.Integer().deserializer()
  implicit val ShortDeserializer      = Serdes.Short().deserializer()
  implicit val LongDeserializer       = Serdes.Long().deserializer()
  implicit val DoubleDeserializer     = Serdes.Double().deserializer()
  implicit val FloatDeserializer      = Serdes.Float().deserializer()
  implicit val BytesDeserializer      = Serdes.Bytes().deserializer()
  implicit val UuidDeserializer       = Serdes.UUID().deserializer()
  implicit val ByteBufferDeserializer = Serdes.ByteBuffer().deserializer()
  implicit val ByteArrDeserializer    = Serdes.ByteArray().deserializer()

}
