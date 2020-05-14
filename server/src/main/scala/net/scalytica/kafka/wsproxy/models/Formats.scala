package net.scalytica.kafka.wsproxy.models

import io.circe.{Decoder, Encoder}
import net.scalytica.kafka.wsproxy.StringExtensions
import net.scalytica.kafka.wsproxy.codecs.{BasicSerdes, Decoders, Encoders}
import org.apache.kafka.common.serialization.{Deserializer, Serializer}

object Formats {

  sealed trait FormatType { self =>

    type Aux

    lazy val name: String = self.getClass.getSimpleName
      .stripSuffix("$")
      .stripSuffix("Type")
      .toSnakeCase

    def isSameName(s: String): Boolean = name.equalsIgnoreCase(s)

    // Convenience access to the relevant Kafka SerDes for the actual type this
    // FormatType describes.
    val serializer: Serializer[Aux]
    val deserializer: Deserializer[Aux]

    // Convenience access to the relevant Circe codecs for the actual type this
    // FormatType describes.
    val encoder: Encoder[Aux]
    val decoder: Decoder[Aux]
  }

  case object NoType extends FormatType {
    type Aux = Unit

    override val serializer   = BasicSerdes.EmptySerializer
    override val deserializer = BasicSerdes.EmptyDeserializer

    override val encoder = Encoder.encodeUnit
    override val decoder = Decoder.decodeUnit
  }

  // Complex types
  case object JsonType extends FormatType {
    type Aux = io.circe.Json

    override val serializer   = BasicSerdes.JsonSerializer
    override val deserializer = BasicSerdes.JsonDeserializer

    override val encoder = Encoder.encodeJson
    override val decoder = Decoder.decodeJson
  }

  case object AvroType extends FormatType {
    type Aux = Array[Byte]

    override val serializer   = BasicSerdes.ByteArrSerializer
    override val deserializer = BasicSerdes.ByteArrDeserializer

    override val encoder = Encoders.byteArrEncoder
    override val decoder = Decoders.byteArrDecoder
  }

  case object ProtobufType extends FormatType {
    type Aux = Array[Byte]

    override val serializer   = BasicSerdes.ByteArrSerializer
    override val deserializer = BasicSerdes.ByteArrDeserializer

    override val encoder = Encoders.byteArrEncoder
    override val decoder = Decoders.byteArrDecoder
  }

  // "Primitives"
  case object ByteArrayType extends FormatType {
    type Aux = Array[Byte]

    override val serializer   = BasicSerdes.ByteArrSerializer
    override val deserializer = BasicSerdes.ByteArrDeserializer

    override val encoder = Encoders.byteArrEncoder
    override val decoder = Decoders.byteArrDecoder
  }

  case object StringType extends FormatType {
    type Aux = String

    override val serializer   = BasicSerdes.StringSerializer
    override val deserializer = BasicSerdes.StringDeserializer

    override val encoder = Encoder.encodeString
    override val decoder = Decoder.decodeString
  }

  case object IntType extends FormatType {
    type Aux = Int

    override val serializer   = BasicSerdes.IntSerializer
    override val deserializer = BasicSerdes.IntDeserializer

    override val encoder = Encoder.encodeInt
    override val decoder = Decoder.decodeInt
  }

  case object ShortType extends FormatType {
    type Aux = Short

    override val serializer   = BasicSerdes.ShortSerializer
    override val deserializer = BasicSerdes.ShortDeserializer

    override val encoder = Encoder.encodeShort
    override val decoder = Decoder.decodeShort
  }

  case object LongType extends FormatType {
    type Aux = Long

    override val serializer   = BasicSerdes.LongSerializer
    override val deserializer = BasicSerdes.LongDeserializer

    override val encoder = Encoder.encodeLong
    override val decoder = Decoder.decodeLong
  }

  case object DoubleType extends FormatType {
    type Aux = Double

    override val serializer   = BasicSerdes.DoubleSerializer
    override val deserializer = BasicSerdes.DoubleDeserializer

    override val encoder = Encoder.encodeDouble
    override val decoder = Decoder.decodeDouble
  }

  case object FloatType extends FormatType {
    type Aux = Float

    override val serializer   = BasicSerdes.FloatSerializer
    override val deserializer = BasicSerdes.FloatDeserializer

    override val encoder = Encoder.encodeFloat
    override val decoder = Decoder.decodeFloat
  }

  object FormatType {

    val All = List(
      JsonType,
      AvroType,
      ProtobufType,
      ByteArrayType,
      StringType,
      IntType,
      ShortType,
      LongType,
      DoubleType,
      FloatType
    )

    // scalastyle:off cyclomatic.complexity
    def fromString(string: String): Option[FormatType] =
      Option(string).flatMap {
        case s: String if JsonType.isSameName(s)      => Some(JsonType)
        case s: String if AvroType.isSameName(s)      => Some(AvroType)
        case s: String if ByteArrayType.isSameName(s) => Some(ByteArrayType)
        case s: String if StringType.isSameName(s)    => Some(StringType)
        case s: String if IntType.isSameName(s)       => Some(IntType)
        case s: String if ShortType.isSameName(s)     => Some(ShortType)
        case s: String if LongType.isSameName(s)      => Some(LongType)
        case s: String if DoubleType.isSameName(s)    => Some(DoubleType)
        case s: String if FloatType.isSameName(s)     => Some(FloatType)
        case _                                        => None
      }
    // scalastyle:on cyclomatic.complexity

    def unsafeFromString(s: String): FormatType =
      fromString(s).getOrElse {
        throw new IllegalArgumentException(s"$s is not a valid format type")
      }

  }

}
