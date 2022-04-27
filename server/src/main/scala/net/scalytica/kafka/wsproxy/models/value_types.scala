package net.scalytica.kafka.wsproxy.models

import java.util.UUID

sealed trait WsIdentifier {
  val value: String
}

/** Identifier for this running instance of the proxy */
case class WsServerId(value: String)

/** Identifier for the consumer group a consumer client belongs to */
case class WsGroupId(value: String) extends WsIdentifier

/** Identifies a connecting client */
case class WsClientId(value: String) extends WsIdentifier

/** Identifier for multiple instance of a producer application */
case class WsProducerId(value: String) extends WsIdentifier

/** Identifier for a single producer client application instance */
case class WsProducerInstanceId(value: String) extends WsIdentifier

object WsGroupId {

  def fromOption(mgid: Option[WsGroupId])(or: WsClientId): WsGroupId =
    mgid.getOrElse(WsGroupId(s"${or.value}-group"))

}

sealed trait FullClientId {
  def value: String
}

case class FullConsumerId(
    groupId: WsGroupId,
    clientId: WsClientId
) extends FullClientId {

  override lazy val value = s"${groupId.value}-${clientId.value}"
}

case class FullProducerId(
    producerId: WsProducerId,
    instanceId: Option[WsProducerInstanceId]
) extends FullClientId {

  val uuid: UUID = UUID.randomUUID()

  override lazy val value =
    s"${producerId.value}-${instanceId.map(_.value).getOrElse(uuid.toString)}"
}

/** The name of a Kafka topic */
case class TopicName(value: String) extends AnyVal
/** The partition number of a Kafka topic */
case class Partition(value: Int) extends AnyVal
/** The consumer offset within a Kafka topic partition */
case class Offset(value: Long) extends AnyVal
/** The timestamp of a given message at an offset in a Kafka topic partition */
case class Timestamp(value: Long) extends AnyVal
