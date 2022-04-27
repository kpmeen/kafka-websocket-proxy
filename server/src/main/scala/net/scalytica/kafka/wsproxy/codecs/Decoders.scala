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
import net.scalytica.kafka.wsproxy.session._

import scala.annotation.nowarn
import scala.util.{Failure, Success}

trait Decoders {

  implicit val brokerInfoDecoder: Decoder[BrokerInfo] = deriveConfiguredDecoder

  implicit val wsClientIdDecoder: Decoder[WsClientId] = { json =>
    json.as[String].map(WsClientId.apply)
  }

  implicit val wsGroupIdDecoder: Decoder[WsGroupId] = { json =>
    json.as[String].map(WsGroupId.apply)
  }

  implicit val wsProducerIdDecoder: Decoder[WsProducerId] = { json =>
    json.as[String].map(WsProducerId.apply)
  }

  implicit val wsProducerInstanceIdDecoder: Decoder[WsProducerInstanceId] = {
    json => json.as[String].map(WsProducerInstanceId.apply)
  }

  implicit val wsServerIdDecoder: Decoder[WsServerId] = { json =>
    json.as[String].map(WsServerId.apply)
  }

  implicit val sessionIdDecoder: Decoder[SessionId] = { json =>
    json.as[String].map(SessionId.apply)
  }

  implicit val fullConsumerIdDecoder: Decoder[FullConsumerId] =
    deriveConfiguredDecoder

  implicit val fullProducerIdDecoder: Decoder[FullProducerId] =
    deriveConfiguredDecoder

  // scalastyle:off
  implicit def deriveClientInstanceDecoder: Decoder[ClientInstance] = {
    @nowarn("msg=is never used")
    implicit val cfg =
      Configuration.default.withDiscriminator("client_instance_type")
    deriveConfiguredDecoder
  }
  // scalastyle:on

  implicit val sessionDecoder: Decoder[Session] = {
    @nowarn("msg=is never used")
    implicit val cfg = Configuration.default.withDiscriminator("session_type")
    deriveConfiguredDecoder
  }

  // ------------------------------------------------------------
  // Decoders for old consumer session format
  implicit val oldConsumerInstanceDecoder: Decoder[OldConsumerInstance] =
    deriveConfiguredDecoder

  implicit val oldSessionDecoder: Decoder[OldSession] =
    deriveConfiguredDecoder
  // ------------------------------------------------------------

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

  implicit val wsCommitDecoder: Decoder[WsCommit] = deriveConfiguredDecoder

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

  implicit val prodResDecoder: Decoder[WsProducerResult] =
    deriveConfiguredDecoder

  implicit val kafkaHeaderDecoder: Decoder[KafkaHeader] =
    deriveConfiguredDecoder

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
      implicit keyDec: Decoder[K],
      valDec: Decoder[V]
  ): Decoder[WsProducerRecord[K, V]] = { cursor =>
    val key   = cursor.downField("key").as[InValueDetails[K]]
    val value = cursor.downField("value").as[InValueDetails[V]]
    val headers =
      cursor.downField("headers").as[Option[Seq[KafkaHeader]]] match {
        case Right(h) => h
        case Left(_)  => None
      }
    val msgId =
      cursor.downField("messageId").as[Option[String]].toOption.flatten

    value match {
      case Right(v) =>
        key match {
          case Right(k) =>
            Right(ProducerKeyValueRecord[K, V](k, v, headers, msgId))
          case Left(_) =>
            Right(ProducerValueRecord[V](v, headers, msgId))
        }

      case Left(fail) =>
        Left(fail)
    }
  }

  implicit def wsConsumerRecordDecoder[K, V](
      implicit keyDec: Decoder[K],
      valDec: Decoder[V]
  ): Decoder[WsConsumerRecord[K, V]] = { cursor =>
    for {
      topic     <- cursor.downField("topic").as[TopicName]
      partition <- cursor.downField("partition").as[Partition]
      offset    <- cursor.downField("offset").as[Offset]
      timestamp <- cursor.downField("timestamp").as[Timestamp]
      headers   <- cursor.downField("headers").as[Option[Seq[KafkaHeader]]]
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
            headers = headers,
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
            headers = headers,
            value = value,
            committableOffset = None
          )
        }
    }
  }
}

object Decoders extends Decoders
