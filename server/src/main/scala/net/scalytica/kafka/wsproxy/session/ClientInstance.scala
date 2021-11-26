package net.scalytica.kafka.wsproxy.session

import net.scalytica.kafka.wsproxy.models.{WsClientId, WsGroupId, WsServerId}

/** Helps to identify a Kafka client instance */
sealed trait ClientInstance {
  val clientId: WsClientId
  val serverId: WsServerId
}

/**
 * Wraps information about each instantiated producer within a
 * [[ProducerSession]].
 *
 * @param clientId
 *   The client (or producer) ID given.
 * @param serverId
 *   The server ID where the producer instance is running.
 */
case class ProducerInstance(clientId: WsClientId, serverId: WsServerId)
    extends ClientInstance

/**
 * Wraps information about each instantiated consumer within a
 * [[ConsumerSession]].
 *
 * @param clientId
 *   The client (or consumer) ID given.
 * @param serverId
 *   The server ID where the consumer instance is running.
 */
case class ConsumerInstance(
    clientId: WsClientId,
    groupId: WsGroupId,
    serverId: WsServerId
) extends ClientInstance
