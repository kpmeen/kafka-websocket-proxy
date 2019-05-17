package net.scalytica.kafka.wsproxy.models

import net.scalytica.kafka.wsproxy.SocketProtocol.SocketPayload
import net.scalytica.kafka.wsproxy.models.Formats.FormatType
import org.apache.kafka.clients.consumer.OffsetResetStrategy

/**
 * Wrapper class for query parameters describing properties for both inbound and
 * outbound traffic through the WebSocket.
 *
 * @param out parameters describing the props for the outbound socket.
 * @param in optional parameters describing the props for the inbound socket.
 */
case class SocketParams(
    out: OutSocketArgs,
    in: Option[InSocketArgs] = None
)

/**
 * Encodes configuration params for an outbound WebSocket stream.
 *
 * @param clientId the clientId to use for the Kafka consumer.
 * @param groupId the groupId to use for the Kafka consumer.
 * @param topic the Kafka topic to subscribe to.
 * @param socketPayload the type of payload to expect through the socket.
 * @param keyType optional type for the message keys in the topic.
 * @param valType the type for the message values in the topic.
 * @param offsetResetStrategy the offset strategy to use. Defaults to EARLIEST
 * @param rateLimit max number of messages per second to pass through.
 * @param batchSize the number messages to include per batch.
 * @param autoCommit enable kafka consumer auto-commit interval.
 */
case class OutSocketArgs(
    clientId: WsClientId,
    groupId: Option[WsGroupId],
    topic: TopicName,
    socketPayload: SocketPayload,
    keyType: Option[Formats.FormatType] = None,
    valType: Formats.FormatType,
    offsetResetStrategy: OffsetResetStrategy = OffsetResetStrategy.EARLIEST,
    rateLimit: Option[Int] = None,
    batchSize: Option[Int] = None,
    autoCommit: Boolean = true
)

object OutSocketArgs {

  // scalastyle:off
  def fromQueryParams(
      clientId: WsClientId,
      groupId: Option[WsGroupId],
      topic: TopicName,
      socketPayload: SocketPayload,
      keyTpe: Option[Formats.FormatType],
      valTpe: Formats.FormatType,
      offsetResetStrategy: OffsetResetStrategy,
      rateLimit: Option[Int],
      batchSize: Option[Int],
      autoCommit: Boolean
  ): OutSocketArgs = OutSocketArgs(
    clientId = clientId,
    groupId = groupId,
    topic = topic,
    socketPayload = socketPayload,
    keyType = keyTpe,
    valType = valTpe,
    offsetResetStrategy = offsetResetStrategy,
    rateLimit = rateLimit,
    batchSize = batchSize,
    autoCommit = autoCommit
  )
  // scalastyle:on
}

/**
 * Encodes configuration params for an inbound WebSocket stream.
 *
 * @param topic the Kafka topic to subscribe to.
 * @param socketPayload the type of payload to expect through the socket.
 * @param keyType optional type for the message keys in the topic.
 * @param valType the type for the message values in the topic.
 */
case class InSocketArgs(
    topic: TopicName,
    socketPayload: SocketPayload,
    keyType: Option[Formats.FormatType] = None,
    valType: Formats.FormatType = Formats.StringType
)

object InSocketArgs {

  def fromOptQueryParams(
      topicName: Option[TopicName],
      socketPayload: SocketPayload,
      keyTpe: Option[FormatType],
      valTpe: Option[FormatType]
  ): Option[InSocketArgs] =
    topicName.map { t =>
      InSocketArgs(
        topic = t,
        socketPayload = socketPayload,
        keyType = keyTpe,
        valType = valTpe.getOrElse(Formats.StringType)
      )
    }

  def fromQueryParams(
      topicName: TopicName,
      socketPayload: SocketPayload,
      keyTpe: Option[FormatType],
      valTpe: FormatType
  ): InSocketArgs =
    InSocketArgs(
      topic = topicName,
      socketPayload = socketPayload,
      keyType = keyTpe,
      valType = valTpe
    )

}
