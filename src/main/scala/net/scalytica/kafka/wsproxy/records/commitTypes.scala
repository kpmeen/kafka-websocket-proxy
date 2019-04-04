package net.scalytica.kafka.wsproxy.records

case class WsMessageId(value: String)

object WsMessageId {

  def apply(
      topic: String,
      partition: Int,
      offset: Long,
      timestamp: Long
  ): WsMessageId = WsMessageId(s"$topic-$partition-$offset-$timestamp")

}

case class WsCommit(wsProxyMessageId: WsMessageId)
