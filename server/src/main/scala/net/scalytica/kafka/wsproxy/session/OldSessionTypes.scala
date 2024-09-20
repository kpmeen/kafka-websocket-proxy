package net.scalytica.kafka.wsproxy.session

import net.scalytica.kafka.wsproxy.models.WsClientId
import net.scalytica.kafka.wsproxy.models.WsGroupId
import net.scalytica.kafka.wsproxy.models.WsServerId

// TODO: This should eventually be removed from the source tree
final case class OldSession(
    consumerGroupId: WsGroupId,
    consumers: Set[OldConsumerInstance],
    consumerLimit: Int
)

final case class OldConsumerInstance(id: WsClientId, serverId: WsServerId)
