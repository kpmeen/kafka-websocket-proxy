package net.scalytica.kafka.wsproxy.models

import akka.http.scaladsl.model.headers.OAuth2BearerToken
import net.scalytica.kafka.wsproxy.models.Formats.FormatType
import net.scalytica.kafka.wsproxy.web.SocketProtocol.SocketPayload
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
 * @param clientId
 *   the clientId to use for the Kafka consumer.
 * @param groupId
 *   the groupId to use for the Kafka consumer.
 * @param topic
 *   the Kafka topic to subscribe to.
 * @param socketPayload
 *   the type of payload to expect through the socket.
 * @param aclCredentials
 *   the Kafka ACL credentials to use with the Kafka client
 * @param keyType
 *   optional type for the message keys in the topic.
 * @param valType
 *   the type for the message values in the topic.
 * @param offsetResetStrategy
 *   the offset strategy to use. Defaults to EARLIEST
 * @param rateLimit
 *   max number of messages per second to pass through.
 * @param batchSize
 *   the number messages to include per batch.
 * @param autoCommit
 *   enable kafka consumer auto-commit interval.
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
    autoCommit: Boolean = true,
    bearerToken: Option[OAuth2BearerToken] = None
) extends SocketArgs {

  def offsetResetStrategyString: String = offsetResetStrategy.name.toLowerCase

  def withAclCredentials(
      aclCredentials: Option[AclCredentials]
  ): OutSocketArgs = copy(aclCredentials = aclCredentials)

  def withBearerToken(token: Option[OAuth2BearerToken]): OutSocketArgs =
    copy(bearerToken = token)
}

object OutSocketArgs {

  type TupledQueryParams = (
      WsClientId,
      Option[WsGroupId],
      TopicName,
      SocketPayload,
      Option[Formats.FormatType],
      Formats.FormatType,
      OffsetResetStrategy,
      Option[Int],
      Option[Int],
      Boolean
  )

  def fromTupledQueryParams(
      t: TupledQueryParams
  ): OutSocketArgs = fromQueryParams(
    clientId = t._1,
    groupId = t._2,
    topic = t._3,
    socketPayload = t._4,
    keyTpe = t._5,
    valTpe = t._6,
    offsetResetStrategy = t._7,
    rateLimit = t._8,
    batchSize = t._9,
    autoCommit = t._10
  )

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
  ): OutSocketArgs =
    OutSocketArgs(
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
 * @param clientId
 *   the clientId to use for the Kafka producer.
 * @param topic
 *   the Kafka topic to subscribe to.
 * @param socketPayload
 *   the type of payload to expect through the socket.
 * @param aclCredentials
 *   the Kafka ACL credentials to use with the Kafka client
 * @param keyType
 *   optional type for the message keys in the topic.
 * @param valType
 *   the type for the message values in the topic.
 */
case class InSocketArgs(
    clientId: WsClientId,
    topic: TopicName,
    socketPayload: SocketPayload,
    aclCredentials: Option[AclCredentials] = None,
    keyType: Option[Formats.FormatType] = None,
    valType: Formats.FormatType = Formats.StringType,
    bearerToken: Option[OAuth2BearerToken] = None
) extends SocketArgs {

  def withAclCredentials(aclCredentials: Option[AclCredentials]): InSocketArgs =
    copy(aclCredentials = aclCredentials)

  def withBearerToken(token: Option[OAuth2BearerToken]): InSocketArgs =
    copy(bearerToken = token)
}

object InSocketArgs {

  type TupledQueryParams = (
      WsClientId,
      TopicName,
      SocketPayload,
      Option[FormatType],
      FormatType
  )

  def fromTupledQueryParams(
      t: TupledQueryParams
  ): InSocketArgs = fromQueryParams(
    clientId = t._1,
    topicName = t._2,
    socketPayload = t._3,
    keyTpe = t._4,
    valTpe = t._5
  )

  def fromQueryParams(
      clientId: WsClientId,
      topicName: TopicName,
      socketPayload: SocketPayload,
      keyTpe: Option[FormatType],
      valTpe: FormatType
  ): InSocketArgs =
    InSocketArgs(
      clientId = clientId,
      topic = topicName,
      socketPayload = socketPayload,
      keyType = keyTpe,
      valType = valTpe
    )

}
