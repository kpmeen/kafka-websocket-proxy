package net.scalytica.kafka.wsproxy.codecs

import io.circe._
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto._
import io.circe.syntax._
import net.scalytica.kafka.wsproxy.models.Formats.FormatType
import net.scalytica.kafka.wsproxy.models.ValueDetails.OutValueDetails
import net.scalytica.kafka.wsproxy.models._
import net.scalytica.kafka.wsproxy.utils.Binary

object Encoders {

  implicit val cfg: Configuration = Configuration.default

  implicit val msgIdEncoder: Encoder[WsMessageId] = { msgId =>
    Json.fromString(msgId.value)
  }

  implicit val byteArrEncoder: Encoder[Array[Byte]] = { arr =>
    Json.fromString(Binary.encodeBase64(arr))
  }

  implicit val formatTypeEncoder: Encoder[FormatType] = { a: FormatType =>
    Json.fromString(a.name)
  }

  implicit val prodResEncoder: Encoder[WsProducerResult] = deriveEncoder

  implicit def outValEncoder[T](
      implicit enc: Encoder[T]
  ): Encoder[OutValueDetails[T]] = { ovd =>
    Json.obj(
      "value"  -> ovd.value.asJson,
      "format" -> ovd.format.asJson
    )
  }

  implicit def wsConsumerRecordEncoder[K, V](
      implicit
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
        "value"            -> cvr.value.asJson
      )
  }

}
