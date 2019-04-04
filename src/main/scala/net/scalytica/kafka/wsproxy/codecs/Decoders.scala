package net.scalytica.kafka.wsproxy.codecs

import io.circe._
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto._
import net.scalytica.kafka.wsproxy.models.Formats.FormatType
import net.scalytica.kafka.wsproxy.models.ValueDetails.InValueDetails
import net.scalytica.kafka.wsproxy.models._
import net.scalytica.kafka.wsproxy.utils.Binary

import scala.util.{Failure, Success}

object Decoders {

  implicit val cfg: Configuration = Configuration.default

  implicit val wsMessageIdDecoder: Decoder[WsMessageId] = { json =>
    json.as[String].map(WsMessageId.apply)
  }

  implicit val wsCommitDecoder: Decoder[WsCommit] = deriveDecoder

  implicit val byteArrDecoder: Decoder[Array[Byte]] = { json =>
    json.as[String].flatMap { s =>
      Binary.decodeBase64(s) match {
        case Success(a) => Right(a)
        case Failure(e) => Left(DecodingFailure.fromThrowable(e, List.empty))
      }
    }
  }

  implicit val formatTypeDecoder: Decoder[FormatType] = { c: io.circe.HCursor =>
    c.value.asString.flatMap(FormatType.fromString).map(Right.apply).getOrElse {
      Left(DecodingFailure("Bad format type", List.empty))
    }
  }

  implicit def inValDecoder[T](
      implicit dec: Decoder[T]
  ): Decoder[InValueDetails[T]] = { json =>
    for {
      v <- json.downField("value").as[T]
      f <- json.downField("format").as[FormatType]
      s <- json.downField("schema").as[Option[String]]
    } yield {
      InValueDetails(v, f, s)
    }
  }

  implicit def jsonToWsProducerRecord[K, V](
      implicit
      keyDec: Decoder[K],
      valDec: Decoder[V]
  ): Decoder[WsProducerRecord[K, V]] = { cursor =>
    val key   = cursor.downField("key").as[InValueDetails[K]]
    val value = cursor.downField("value").as[InValueDetails[V]]

    value match {
      case Right(v) =>
        key match {
          case Right(k) => Right(ProducerKeyValueRecord[K, V](k, v))
          case Left(_)  => Right(ProducerValueRecord[V](v))
        }

      case Left(fail) =>
        Left(fail)
    }
  }
}
