package net.scalytica.kafka.wsproxy

import org.apache.kafka.common.serialization.{Serdes => KSerdes}

object BasicSerdes {

  implicit val StringSerializer     = KSerdes.String().serializer()
  implicit val IntSerializer        = KSerdes.Integer().serializer()
  implicit val ShortSerializer      = KSerdes.Short().serializer()
  implicit val LongSerializer       = KSerdes.Long().serializer()
  implicit val DoubleSerializer     = KSerdes.Double().serializer()
  implicit val FloatSerializer      = KSerdes.Float().serializer()
  implicit val BytesSerializer      = KSerdes.Bytes().serializer()
  implicit val UuidSerializer       = KSerdes.UUID().serializer()
  implicit val ByteBufferSerializer = KSerdes.ByteBuffer().serializer()
  implicit val ByteArrSerializer    = KSerdes.ByteArray().serializer()

  implicit val StringDeserializer     = KSerdes.String().deserializer()
  implicit val IntDeserializer        = KSerdes.Integer().deserializer()
  implicit val ShortDeserializer      = KSerdes.Short().deserializer()
  implicit val LongDeserializer       = KSerdes.Long().deserializer()
  implicit val DoubleDeserializer     = KSerdes.Double().deserializer()
  implicit val FloatDeserializer      = KSerdes.Float().deserializer()
  implicit val BytesDeserializer      = KSerdes.Bytes().deserializer()
  implicit val UuidDeserializer       = KSerdes.UUID().deserializer()
  implicit val ByteBufferDeserializer = KSerdes.ByteBuffer().deserializer()
  implicit val ByteArrDeserializer    = KSerdes.ByteArray().deserializer()

}
