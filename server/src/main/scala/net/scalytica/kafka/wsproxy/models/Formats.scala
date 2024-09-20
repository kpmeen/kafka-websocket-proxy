package net.scalytica.kafka.wsproxy.models

import net.scalytica.kafka.wsproxy.NiceClassNameExtensions
import net.scalytica.kafka.wsproxy.StringExtensions
import net.scalytica.kafka.wsproxy.codecs.BasicSerdes

import io.circe.Decoder
import io.circe.Encoder
import io.circe.Json
import org.apache.kafka.common.serialization.Deserializer
import org.apache.kafka.common.serialization.Serializer

object Formats {

  sealed trait FormatType { self =>

    type Aux
    type Tpe

    lazy val name: String =
      self.niceClassSimpleName.stripSuffix("Type").toSnakeCase

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
    type Tpe = Unit

    override val serializer: Serializer[Unit] = BasicSerdes.EmptySerializer
    override val deserializer: Deserializer[Unit] =
      BasicSerdes.EmptyDeserializer

    override val encoder: Encoder[Unit] = Encoder.encodeUnit
    override val decoder: Decoder[Unit] = Decoder.decodeUnit
  }

  // Complex types
  case object JsonType extends FormatType {
    type Aux = io.circe.Json
    type Tpe = String

    override val serializer: Serializer[Json]     = BasicSerdes.JsonSerializer
    override val deserializer: Deserializer[Json] = BasicSerdes.JsonDeserializer

    override val encoder: Encoder[Json] = Encoder.encodeJson
    override val decoder: Decoder[Json] = Decoder.decodeJson
  }

  case object StringType extends FormatType {
    type Aux = String
    type Tpe = String

    override val serializer: Serializer[String] = BasicSerdes.StringSerializer
    override val deserializer: Deserializer[String] =
      BasicSerdes.StringDeserializer

    override val encoder: Encoder[String] = Encoder.encodeString
    override val decoder: Decoder[String] = Decoder.decodeString
  }

  case object IntType extends FormatType {
    type Aux = Int
    type Tpe = Int

    override val serializer: Serializer[Int]     = BasicSerdes.IntSerializer
    override val deserializer: Deserializer[Int] = BasicSerdes.IntDeserializer

    override val encoder: Encoder[Int] = Encoder.encodeInt
    override val decoder: Decoder[Int] = Decoder.decodeInt
  }

  case object LongType extends FormatType {
    type Aux = Long
    type Tpe = Long

    override val serializer: Serializer[Long]     = BasicSerdes.LongSerializer
    override val deserializer: Deserializer[Long] = BasicSerdes.LongDeserializer

    override val encoder: Encoder[Long] = Encoder.encodeLong
    override val decoder: Decoder[Long] = Decoder.decodeLong
  }

  case object DoubleType extends FormatType {
    type Aux = Double
    type Tpe = Double

    override val serializer: Serializer[Double] = BasicSerdes.DoubleSerializer
    override val deserializer: Deserializer[Double] =
      BasicSerdes.DoubleDeserializer

    override val encoder: Encoder[Double] = Encoder.encodeDouble
    override val decoder: Decoder[Double] = Decoder.decodeDouble
  }

  case object FloatType extends FormatType {
    type Aux = Float
    type Tpe = Float

    override val serializer: Serializer[Float] = BasicSerdes.FloatSerializer
    override val deserializer: Deserializer[Float] =
      BasicSerdes.FloatDeserializer

    override val encoder: Encoder[Float] = Encoder.encodeFloat
    override val decoder: Decoder[Float] = Decoder.decodeFloat
  }

  object FormatType {

    val All = List(
      JsonType,
      StringType,
      IntType,
      LongType,
      DoubleType,
      FloatType
    )

    // scalastyle:off cyclomatic.complexity
    def fromString(string: String): Option[FormatType] =
      Option(string).flatMap {
        case s: String if JsonType.isSameName(s)   => Some(JsonType)
        case s: String if StringType.isSameName(s) => Some(StringType)
        case s: String if IntType.isSameName(s)    => Some(IntType)
        case s: String if LongType.isSameName(s)   => Some(LongType)
        case s: String if DoubleType.isSameName(s) => Some(DoubleType)
        case s: String if FloatType.isSameName(s)  => Some(FloatType)
        case _                                     => None
      }
    // scalastyle:on cyclomatic.complexity

    def unsafeFromString(s: String): FormatType =
      fromString(s).getOrElse {
        throw new IllegalArgumentException(s"'$s' is not a valid format type")
      }

  }

}
