package net.scalytica.kafka.wsproxy

import io.circe._
import io.circe.generic.extras.Configuration
import io.circe.syntax._
import io.circe.generic.extras.semiauto._
import net.scalytica.kafka.wsproxy.Formats.FormatType
import net.scalytica.kafka.wsproxy.records._

object Encoders {

  implicit val cfg: Configuration = Configuration.default

  implicit val formatTypeEncoder: Encoder[FormatType] = { a: FormatType =>
    Json.fromString(a.name)
  }

  implicit val outValStrEncoder: Encoder[OutValueDetails[String]] =
    deriveEncoder
  implicit val outValByteArrEncoder: Encoder[OutValueDetails[Array[Byte]]] =
    deriveEncoder

  implicit val consRecStrStrEnc: Encoder[WsConsumerRecord[String, String]] = {
    cr =>
      wsConsumerRecordToJson[String, String](cr)
  }

  implicit val consRecStrByteArrEnc
    : Encoder[WsConsumerRecord[String, Array[Byte]]] = { cr =>
    wsConsumerRecordToJson[String, Array[Byte]](cr)
  }

  implicit val consRecArrByteEnc
    : Encoder[WsConsumerRecord[Array[Byte], Array[Byte]]] = { cr =>
    wsConsumerRecordToJson[Array[Byte], Array[Byte]](cr)
  }

  private[this] def wsConsumerRecordToJson[K, V](cr: WsConsumerRecord[K, V])(
      implicit
      keyEnc: Encoder[OutValueDetails[K]],
      valEnc: Encoder[OutValueDetails[V]]
  ): Json = cr match {
    case ckvr: ConsumerKeyValueRecord[K, V] =>
      Json.obj(
        "partition" -> Json.fromInt(ckvr.partition),
        "offset"    -> Json.fromLong(ckvr.offset),
        "key"       -> ckvr.key.asJson,
        "value"     -> ckvr.value.asJson
      )

    case cvr: ConsumerValueRecord[V] =>
      Json.obj(
        "partition" -> Json.fromInt(cvr.partition),
        "offset"    -> Json.fromLong(cvr.offset),
        "value"     -> cvr.value.asJson
      )
  }

}

object Decoders {

  implicit val cfg: Configuration = Configuration.default

  implicit val formatTypeDecoder: Decoder[FormatType] = { c: io.circe.HCursor =>
    c.value.asString.flatMap(FormatType.fromString).map(Right.apply).getOrElse {
      Left(DecodingFailure("Bad format type", List.empty))
    }
  }

  implicit val inValStrDecoder: Decoder[InValueDetails[String]] = deriveDecoder
  implicit val inValByteArrDecoder: Decoder[InValueDetails[Array[Byte]]] =
    deriveDecoder

}
