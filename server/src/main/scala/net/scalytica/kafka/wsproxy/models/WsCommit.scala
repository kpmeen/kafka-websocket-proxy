package net.scalytica.kafka.wsproxy.models

import net.scalytica.kafka.wsproxy.avro.SchemaTypes.AvroCommit

/**
 * Model for capturing inbound offset commit messages from clients.
 *
 * @param wsProxyMessageId the [[WsMessageId]] for the message.
 */
case class WsCommit(wsProxyMessageId: WsMessageId)

object WsCommit {

  def fromAvro(a: AvroCommit): WsCommit = {
    WsCommit(WsMessageId(a.wsProxyMessageId))
  }

}
