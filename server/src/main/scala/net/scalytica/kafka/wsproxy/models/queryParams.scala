package net.scalytica.kafka.wsproxy.models

import net.scalytica.kafka.wsproxy.SocketProtocol.SocketPayload
import net.scalytica.kafka.wsproxy.models.Formats.FormatType
import org.apache.kafka.clients.consumer.OffsetResetStrategy

trait SocketArgs {
  val topic: TopicName
  val socketPayload: SocketPayload
  val keyType: Option[Formats.FormatType]
  val valType: Formats.FormatType
}

/**
 * Encodes configuration params for an outbound WebSocket stream.
 *
 * @param clientId the clientId to use for the Kafka consumer.
 * @param groupId the groupId to use for the Kafka consumer.
 * @param topic the Kafka topic to subscribe to.
 * @param socketPayload the type of payload to expect through the socket.
 * @param aclCredentials the Kafka ACL credentials to use with the Kafka client
 * @param keyType optional type for the message keys in the topic.
 * @param valType the type for the message values in the topic.
 * @param offsetResetStrategy the offset strategy to use. Defaults to EARLIEST
 * @param rateLimit max number of messages per second to pass through.
 * @param batchSize the number messages to include per batch.
 * @param autoCommit enable kafka consumer auto-commit interval.
 */
case class OutSocketArgs(
    clientId: WsClientId,
    groupId: WsGroupId,
    topic: TopicName,
    socketPayload: SocketPayload,
    aclCredentials: Option[AclCredentials] = None,
    keyType: Option[Formats.FormatType] = None,
    valType: Formats.FormatType,
    offsetResetStrategy: OffsetResetStrategy = OffsetResetStrategy.EARLIEST,
    rateLimit: Option[Int] = None,
    batchSize: Option[Int] = None,
    autoCommit: Boolean = true
) extends SocketArgs {

  def withAclCredentials(
      aclCredentials: Option[AclCredentials]
  ): OutSocketArgs = copy(aclCredentials = aclCredentials)
}

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
    groupId = WsGroupId.fromOption(groupId)(clientId),
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
 * @param aclCredentials the Kafka ACL credentials to use with the Kafka client
 * @param keyType optional type for the message keys in the topic.
 * @param valType the type for the message values in the topic.
 */
case class InSocketArgs(
    topic: TopicName,
    socketPayload: SocketPayload,
    aclCredentials: Option[AclCredentials] = None,
    keyType: Option[Formats.FormatType] = None,
    valType: Formats.FormatType = Formats.StringType
) extends SocketArgs {

  def withAclCredentials(aclCredentials: Option[AclCredentials]): InSocketArgs =
    copy(aclCredentials = aclCredentials)

}

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
