package net.scalytica.kafka.wsproxy.models

/**
 * Model for capturing inbound offset commit messages from clients.
 *
 * @param wsProxyMessageId the [[WsMessageId]] for the message.
 */
case class WsCommit(wsProxyMessageId: WsMessageId)
