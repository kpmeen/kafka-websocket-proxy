package net.scalytica.kafka.wsproxy.session

import net.scalytica.kafka.wsproxy.models.{WsClientId, WsGroupId, WsServerId}

// TODO: This should eventually be removed from the source tree
final case class OldSession(
    consumerGroupId: WsGroupId,
    consumers: Set[OldConsumerInstance],
    consumerLimit: Int
)

final case class OldConsumerInstance(id: WsClientId, serverId: WsServerId)
