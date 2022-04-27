package net.scalytica.kafka.wsproxy.session

import net.scalytica.kafka.wsproxy.models.{
  FullClientId,
  FullConsumerId,
  FullProducerId,
  WsServerId
}

/** Helps to identify a Kafka client instance */
sealed trait ClientInstance {
  val id: FullClientId
  val serverId: WsServerId
}

/**
 * Wraps information about each instantiated producer within a
 * [[ProducerSession]].
 *
 * @param id
 *   The full client (or producer) ID and instance ID given.
 * @param serverId
 *   The server ID where the producer instance is running.
 */
case class ProducerInstance(
    id: FullProducerId,
    serverId: WsServerId
) extends ClientInstance {
  val producerId = id.producerId
  val instanceId = id.instanceId
}

/**
 * Wraps information about each instantiated consumer within a
 * [[ConsumerSession]].
 *
 * @param id
 *   The full client (or consumer) ID and group ID given.
 * @param serverId
 *   The server ID where the consumer instance is running.
 */
case class ConsumerInstance(
    id: FullConsumerId,
    serverId: WsServerId
) extends ClientInstance {
  val clientId = id.clientId
  val groupId  = id.groupId
}
