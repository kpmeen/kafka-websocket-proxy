package net.scalytica.kafka.wsproxy.codecs

import io.circe._
import io.circe.generic.extras.semiauto._
import io.circe.syntax._
import net.scalytica.kafka.wsproxy.models.Formats.FormatType
import net.scalytica.kafka.wsproxy.models.ValueDetails.OutValueDetails
import net.scalytica.kafka.wsproxy.models._
import net.scalytica.kafka.wsproxy.session.{ConsumerInstance, Session}

trait Encoders {

  implicit val brokerInfoEncoder: Encoder[BrokerInfo] = deriveConfiguredEncoder

  implicit val sessionEncoder: Encoder[Session] = deriveConfiguredEncoder

  implicit val consumerInstEncoder: Encoder[ConsumerInstance] =
    deriveConfiguredEncoder

  implicit val msgIdEncoder: Encoder[WsMessageId] = { msgId =>
    Json.fromString(msgId.value)
  }

  implicit val clientIdEncoder: Encoder[WsClientId] = { cid =>
    Json.fromString(cid.value)
  }

  implicit val groupIdEncoder: Encoder[WsGroupId] = { gid =>
    Json.fromString(gid.value)
  }

  implicit val serverIdEncoder: Encoder[WsServerId] = { sid =>
    Json.fromString(sid.value)
  }

  implicit val topicNameEncoder: Encoder[TopicName] = { tn =>
    Json.fromString(tn.value)
  }

  implicit val partitionEncoder: Encoder[Partition] = { partition =>
    Json.fromInt(partition.value)
  }

  implicit val offsetEncoder: Encoder[Offset] = { offset =>
    Json.fromLong(offset.value)
  }

  implicit val timestampEncoder: Encoder[Timestamp] = { ts =>
    Json.fromLong(ts.value)
  }

  implicit val byteArrEncoder: Encoder[Array[Byte]] = { arr =>
    Json.fromString(Binary.encodeBase64(arr))
  }

  implicit val formatTypeEncoder: Encoder[FormatType] = { a: FormatType =>
    Json.fromString(a.name)
  }

  implicit val prodResEncoder: Encoder[WsProducerResult] =
    deriveConfiguredEncoder

  implicit val kafkaHeaderEncoder: Encoder[KafkaHeader] =
    deriveConfiguredEncoder

  implicit def outValEncoder[T](implicit
      enc: Encoder[T]
  ): Encoder[OutValueDetails[T]] = { ovd =>
    Json.obj(
      "value"  -> ovd.value.asJson,
      "format" -> ovd.format.asJson
    )
  }

  implicit def wsConsumerRecordEncoder[K, V](implicit
      keyEnc: Encoder[OutValueDetails[K]],
      valEnc: Encoder[OutValueDetails[V]]
  ): Encoder[WsConsumerRecord[K, V]] = {
    case ckvr: ConsumerKeyValueRecord[K, V] =>
      Json.obj(
        "wsProxyMessageId" -> ckvr.wsProxyMessageId.asJson,
        "topic"            -> ckvr.topic.asJson,
        "partition"        -> ckvr.partition.asJson,
        "offset"           -> ckvr.offset.asJson,
        "timestamp"        -> ckvr.timestamp.asJson,
        "headers"          -> ckvr.headers.asJson,
        "key"              -> ckvr.key.asJson,
        "value"            -> ckvr.value.asJson
      )

    case cvr: ConsumerValueRecord[V] =>
      Json.obj(
        "wsProxyMessageId" -> cvr.wsProxyMessageId.asJson,
        "topic"            -> cvr.topic.asJson,
        "partition"        -> cvr.partition.asJson,
        "offset"           -> cvr.offset.asJson,
        "timestamp"        -> cvr.timestamp.asJson,
        "headers"          -> cvr.headers.asJson,
        "value"            -> cvr.value.asJson
      )
  }

}

object Encoders extends Encoders
