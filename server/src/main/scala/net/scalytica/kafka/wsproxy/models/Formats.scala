package net.scalytica.kafka.wsproxy.models

import io.circe.{Decoder, Encoder}
import net.scalytica.kafka.wsproxy.avro.SchemaTypes.AvroValueTypesCoproduct
import net.scalytica.kafka.wsproxy.codecs.{BasicSerdes, Decoders, Encoders}
import net.scalytica.kafka.wsproxy.errors.IllegalFormatTypeError
import net.scalytica.kafka.wsproxy.logging.DefaultProxyLogger
import net.scalytica.kafka.wsproxy.{
  NiceClassNameExtensions,
  OptionExtensions,
  StringExtensions
}
import org.apache.kafka.common.serialization.{Deserializer, Serializer}
import shapeless.Coproduct

import scala.util.Try
import scala.util.control.NonFatal

object Formats {

  sealed trait FormatType { self =>

    type Aux
    type Tpe

    def fromCoproduct[T](co: AvroValueTypesCoproduct): Option[T]

    def unsafeFromCoproduct[T](co: AvroValueTypesCoproduct): T =
      fromCoproduct[T](co).getOrElse(
        throw new IllegalArgumentException(s"Wrong type for $co")
      )

    def toCoproduct(v: Tpe): Option[AvroValueTypesCoproduct]

    def typeToCoproduct(v: Tpe): AvroValueTypesCoproduct

    def anyToCoproduct(v: Any): AvroValueTypesCoproduct =
      Try(v.asInstanceOf[Tpe])
        .recover { case NonFatal(e) =>
          DefaultProxyLogger.warn(
            s"Could not cast ${v.getClass} to ${self.getClass}",
            e
          )
          throw e
        }
        .toOption
        .map(typeToCoproduct)
        .orThrow(
          IllegalFormatTypeError(
            s"The Type ${v.getClass} is not a valid String."
          )
        )

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

    def fromCoproduct[T](co: AvroValueTypesCoproduct): Option[T] = None
    def toCoproduct(v: Tpe): Option[AvroValueTypesCoproduct]     = None

    def typeToCoproduct(v: Tpe): AvroValueTypesCoproduct =
      throw new IllegalAccessError(
        s"$getClass cannot be converted to Coproduct."
      )

    override val serializer   = BasicSerdes.EmptySerializer
    override val deserializer = BasicSerdes.EmptyDeserializer

    override val encoder = Encoder.encodeUnit
    override val decoder = Decoder.decodeUnit
  }

  // Complex types
  case object JsonType extends FormatType {
    type Aux = io.circe.Json
    type Tpe = String

    def fromCoproduct[T](co: AvroValueTypesCoproduct): Option[T] =
      co.select[Tpe].map(_.asInstanceOf[T])

    def toCoproduct(v: Tpe): Option[AvroValueTypesCoproduct] =
      Some(Coproduct[AvroValueTypesCoproduct](v))

    def typeToCoproduct(v: Tpe): AvroValueTypesCoproduct =
      Coproduct[AvroValueTypesCoproduct](v)

    override val serializer   = BasicSerdes.JsonSerializer
    override val deserializer = BasicSerdes.JsonDeserializer

    override val encoder = Encoder.encodeJson
    override val decoder = Decoder.decodeJson
  }

  sealed trait BaseBinaryType extends FormatType {
    type Aux = Array[Byte]
    type Tpe = Array[Byte]

    def fromCoproduct[T](co: AvroValueTypesCoproduct): Option[T] =
      co.select[Tpe].map(_.asInstanceOf[T])

    def toCoproduct(v: Tpe): Option[AvroValueTypesCoproduct] =
      Some(Coproduct[AvroValueTypesCoproduct](v))

    def typeToCoproduct(v: Tpe): AvroValueTypesCoproduct =
      Coproduct[AvroValueTypesCoproduct](v)

    override val serializer   = BasicSerdes.ByteArrSerializer
    override val deserializer = BasicSerdes.ByteArrDeserializer

    override val encoder = Encoders.byteArrEncoder
    override val decoder = Decoders.byteArrDecoder
  }

  case object AvroType extends BaseBinaryType

  // "Primitives"
  case object ByteArrayType extends BaseBinaryType

  case object StringType extends FormatType {
    type Aux = String
    type Tpe = String

    def fromCoproduct[T](co: AvroValueTypesCoproduct): Option[T] =
      co.select[Tpe].map(_.asInstanceOf[T])

    def toCoproduct(v: Tpe): Option[AvroValueTypesCoproduct] =
      Some(Coproduct[AvroValueTypesCoproduct](v))

    def typeToCoproduct(v: Tpe): AvroValueTypesCoproduct =
      Coproduct[AvroValueTypesCoproduct](v)

    override val serializer   = BasicSerdes.StringSerializer
    override val deserializer = BasicSerdes.StringDeserializer

    override val encoder = Encoder.encodeString
    override val decoder = Decoder.decodeString
  }

  case object IntType extends FormatType {
    type Aux = Int
    type Tpe = Int

    def fromCoproduct[T](co: AvroValueTypesCoproduct): Option[T] =
      co.select[Tpe].map(_.asInstanceOf[T])

    def toCoproduct(v: Tpe): Option[AvroValueTypesCoproduct] =
      Some(Coproduct[AvroValueTypesCoproduct](v))

    def typeToCoproduct(v: Tpe): AvroValueTypesCoproduct =
      Coproduct[AvroValueTypesCoproduct](v)

    override val serializer   = BasicSerdes.IntSerializer
    override val deserializer = BasicSerdes.IntDeserializer

    override val encoder = Encoder.encodeInt
    override val decoder = Decoder.decodeInt
  }

  case object LongType extends FormatType {
    type Aux = Long
    type Tpe = Long

    def fromCoproduct[T](co: AvroValueTypesCoproduct): Option[T] =
      co.select[Tpe].map(_.asInstanceOf[T])

    def toCoproduct(v: Tpe): Option[AvroValueTypesCoproduct] =
      Some(Coproduct[AvroValueTypesCoproduct](v))

    def typeToCoproduct(v: Tpe): AvroValueTypesCoproduct =
      Coproduct[AvroValueTypesCoproduct](v)

    override val serializer   = BasicSerdes.LongSerializer
    override val deserializer = BasicSerdes.LongDeserializer

    override val encoder = Encoder.encodeLong
    override val decoder = Decoder.decodeLong
  }

  case object DoubleType extends FormatType {
    type Aux = Double
    type Tpe = Double

    def fromCoproduct[T](co: AvroValueTypesCoproduct): Option[T] =
      co.select[Tpe].map(_.asInstanceOf[T])

    def toCoproduct(v: Tpe): Option[AvroValueTypesCoproduct] =
      Some(Coproduct[AvroValueTypesCoproduct](v))

    def typeToCoproduct(v: Tpe): AvroValueTypesCoproduct =
      Coproduct[AvroValueTypesCoproduct](v)

    override val serializer   = BasicSerdes.DoubleSerializer
    override val deserializer = BasicSerdes.DoubleDeserializer

    override val encoder = Encoder.encodeDouble
    override val decoder = Decoder.decodeDouble
  }

  case object FloatType extends FormatType {
    type Aux = Float
    type Tpe = Float

    def fromCoproduct[T](co: AvroValueTypesCoproduct): Option[T] =
      co.select[Tpe].map(_.asInstanceOf[T])

    def toCoproduct(v: Tpe): Option[AvroValueTypesCoproduct] =
      Some(Coproduct[AvroValueTypesCoproduct](v))

    def typeToCoproduct(v: Tpe): AvroValueTypesCoproduct =
      Coproduct[AvroValueTypesCoproduct](v)

    override val serializer   = BasicSerdes.FloatSerializer
    override val deserializer = BasicSerdes.FloatDeserializer

    override val encoder = Encoder.encodeFloat
    override val decoder = Decoder.decodeFloat
  }

  object FormatType {

    val All = List(
      JsonType,
      AvroType,
      ByteArrayType,
      StringType,
      IntType,
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
        case s: String if LongType.isSameName(s)      => Some(LongType)
        case s: String if DoubleType.isSameName(s)    => Some(DoubleType)
        case s: String if FloatType.isSameName(s)     => Some(FloatType)
        case _                                        => None
      }
    // scalastyle:on cyclomatic.complexity

    def unsafeFromString(s: String): FormatType =
      fromString(s).getOrElse {
        throw new IllegalArgumentException(s"'$s' is not a valid format type")
      }

  }

}
