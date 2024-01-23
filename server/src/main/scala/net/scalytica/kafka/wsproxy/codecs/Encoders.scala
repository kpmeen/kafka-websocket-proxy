package net.scalytica.kafka.wsproxy.codecs

import io.circe._
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto._
import io.circe.syntax._
import io.circe.parser._
import net.scalytica.kafka.wsproxy.config.Configuration.DynamicCfg
import net.scalytica.kafka.wsproxy.models.Formats.FormatType
import net.scalytica.kafka.wsproxy.models.ValueDetails.OutValueDetails
import net.scalytica.kafka.wsproxy.models._
import net.scalytica.kafka.wsproxy.session._

import scala.annotation.nowarn

trait Encoders {

  implicit val brokerInfoEncoder: Encoder[BrokerInfo] = deriveConfiguredEncoder

  implicit val wsClientIdEncoder: Encoder[WsClientId] = { cid =>
    Json.fromString(cid.value)
  }

  implicit val wsGroupIdEncoder: Encoder[WsGroupId] = { gid =>
    Json.fromString(gid.value)
  }

  implicit val wsProducerIdEncoder: Encoder[WsProducerId] = { pid =>
    Json.fromString(pid.value)
  }

  implicit val wsProducerInstanceIdEncoder: Encoder[WsProducerInstanceId] = {
    piid => Json.fromString(piid.value)
  }

  implicit val wsServerIdEncoder: Encoder[WsServerId] = { sid =>
    Json.fromString(sid.value)
  }

  implicit val sessionIdEncoder: Encoder[SessionId] = { sid =>
    Json.fromString(sid.value)
  }

  implicit val fullConsumerIdEncoder: Encoder[FullConsumerId] =
    deriveConfiguredEncoder

  implicit val fullProducerIdEncoder: Encoder[FullProducerId] =
    deriveConfiguredEncoder

  // scalastyle:off
  implicit val sessionClientInstanceEncoder: Encoder[ClientInstance] = {
    @nowarn("msg=is never used")
    implicit val cfg: Configuration =
      Configuration.default.withDiscriminator("client_instance_type")
    deriveConfiguredEncoder
  }
  // scalastyle:on

  implicit val sessionEncoder: Encoder[Session] = {
    @nowarn("msg=is never used")
    implicit val cfg: Configuration =
      Configuration.default.withDiscriminator("session_type")
    deriveConfiguredEncoder
  }

  implicit val msgIdEncoder: Encoder[WsMessageId] = { msgId =>
    Json.fromString(msgId.value)
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

  implicit val dynamicCfgEncoder: Encoder[DynamicCfg] = { dcfg =>
    parse(dcfg.asHoconString(useJson = true)) match {
      case Right(value) => value
      case Left(err)    => Json.obj("error" -> Json.fromString(err.message))
    }
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

  implicit def outValEncoder[T](
      implicit enc: Encoder[T]
  ): Encoder[OutValueDetails[T]] = { ovd =>
    Json.obj(
      "value"  -> ovd.value.asJson,
      "format" -> ovd.format.asJson
    )
  }

  implicit def wsConsumerRecordEncoder[K, V](
      implicit keyEnc: Encoder[OutValueDetails[K]],
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
