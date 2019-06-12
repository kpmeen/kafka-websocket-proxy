package net.scalytica.kafka.wsproxy.models

case class WsGroupId(value: String)

object WsGroupId {

  def fromOption(mgid: Option[WsGroupId])(or: WsClientId): WsGroupId =
    mgid.getOrElse(WsGroupId(s"$or-group"))

}

case class WsClientId(value: String) extends AnyVal
case class WsServerId(value: String) extends AnyVal

case class TopicName(value: String) extends AnyVal
case class Partition(value: Int)    extends AnyVal
case class Offset(value: Long)      extends AnyVal
case class Timestamp(value: Long)   extends AnyVal
