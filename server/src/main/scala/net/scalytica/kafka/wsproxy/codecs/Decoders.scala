package net.scalytica.kafka.wsproxy.codecs

import io.circe._
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto._
import net.scalytica.kafka.wsproxy.models.Formats.FormatType
import net.scalytica.kafka.wsproxy.models.ValueDetails.{
  InValueDetails,
  OutValueDetails
}
import net.scalytica.kafka.wsproxy.models._
import net.scalytica.kafka.wsproxy.utils.Binary

import scala.util.{Failure, Success}

object Decoders {

  implicit val cfg: Configuration = Configuration.default

  implicit val wsMessageIdDecoder: Decoder[WsMessageId] = { json =>
    json.as[String].map(WsMessageId.apply)
  }

  implicit val topicNameDecoder: Decoder[TopicName] = { json =>
    json.as[String].map(TopicName.apply)
  }

  implicit val partitionDecoder: Decoder[Partition] = { json =>
    json.as[Int].map(Partition.apply)
  }

  implicit val offsetDecoder: Decoder[Offset] = { json =>
    json.as[Long].map(Offset.apply)
  }

  implicit val timestampDecoder: Decoder[Timestamp] = { json =>
    json.as[Long].map(Timestamp.apply)
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

  implicit val prodResDecoder: Decoder[WsProducerResult] = deriveDecoder

  implicit def inValDecoder[T](
      implicit dec: Decoder[T]
  ): Decoder[InValueDetails[T]] = { json =>
    for {
      v <- json.downField("value").as[T]
      f <- json.downField("format").as[FormatType]
    } yield {
      InValueDetails(v, f)
    }
  }

  implicit def outValDecoder[T](
      implicit dec: Decoder[T]
  ): Decoder[OutValueDetails[T]] = { json =>
    for {
      v <- json.downField("value").as[T]
      f <- json.downField("format").as[Option[FormatType]]
    } yield {
      OutValueDetails(v, f)
    }
  }

  implicit def wsProducerRecordDecoder[K, V](
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

  implicit def wsConsumerRecordDecoder[K, V](
      implicit
      keyDec: Decoder[K],
      valDec: Decoder[V]
  ): Decoder[WsConsumerRecord[K, V]] = { cursor =>
    for {
      msgId     <- cursor.downField("wsProxyMessageId").as[WsMessageId]
      topic     <- cursor.downField("topic").as[TopicName]
      partition <- cursor.downField("partition").as[Partition]
      offset    <- cursor.downField("offset").as[Offset]
      timestamp <- cursor.downField("timestamp").as[Timestamp]
      key       <- cursor.downField("key").as[Option[OutValueDetails[K]]]
      value     <- cursor.downField("value").as[OutValueDetails[V]]
    } yield {
      key
        .map { k =>
          ConsumerKeyValueRecord(
            topic = topic,
            partition = partition,
            offset = offset,
            timestamp = timestamp,
            key = k,
            value = value,
            committableOffset = None
          )
        }
        .getOrElse {
          ConsumerValueRecord(
            topic = topic,
            partition = partition,
            offset = offset,
            timestamp,
            value = value,
            committableOffset = None
          )
        }
    }
  }
}
