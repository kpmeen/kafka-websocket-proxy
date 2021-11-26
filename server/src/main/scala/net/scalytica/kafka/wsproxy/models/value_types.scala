package net.scalytica.kafka.wsproxy.models

/** Identifier for this running instance of the proxy */
case class WsServerId(value: String)

/** Identifies a consumer client */
case class WsClientId(value: String)

/** Identifier for the consumer group a consumer client belongs to */
case class WsGroupId(value: String)

object WsGroupId {

  def fromOption(mgid: Option[WsGroupId])(or: WsClientId): WsGroupId =
    mgid.getOrElse(WsGroupId(s"${or.value}-group"))

}

/** The name of a Kafka topic */
case class TopicName(value: String) extends AnyVal
/** The partition number of a Kafka topic */
case class Partition(value: Int) extends AnyVal
/** The consumer offset within a Kafka topic partition */
case class Offset(value: Long) extends AnyVal
/** The timestamp of a given message at an offset in a Kafka topic partition */
case class Timestamp(value: Long) extends AnyVal
