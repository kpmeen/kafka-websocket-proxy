package net.scalytica.kafka.wsproxy.models

import akka.http.scaladsl.model.headers.OAuth2BearerToken
import net.scalytica.kafka.wsproxy.models.Formats.FormatType
import net.scalytica.kafka.wsproxy.web.SocketProtocol.SocketPayload
import org.apache.kafka.clients.consumer.OffsetResetStrategy

sealed trait SocketArgs {
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
 * @param keyType
 *   optional type for the message keys in the topic.
 * @param valType
 *   the type for the message values in the topic.
 * @param offsetResetStrategy
 *   the offset strategy to use. Defaults to EARLIEST
 * @param isolationLevel
 *   Controls how to read messages written transactionally. If set to
 *   [[ReadCommitted]], the consumer will only return transactional messages
 *   which have been committed. If set to [[ReadCommitted]] (the default), the
 *   consumer will return all messages, even transactional messages which have
 *   been aborted. Non-transactional messages will be returned unconditionally
 *   in either mode.
 * @param rateLimit
 *   max number of messages per second to pass through.
 * @param batchSize
 *   the number messages to include per batch.
 * @param autoCommit
 *   enable kafka consumer auto-commit interval.
 * @param aclCredentials
 *   the Kafka ACL credentials to use with the Kafka client
 * @param bearerToken
 *   if present, contains the auth bearer token
 */
case class OutSocketArgs(
    clientId: WsClientId,
    groupId: WsGroupId,
    topic: TopicName,
    socketPayload: SocketPayload,
    keyType: Option[Formats.FormatType] = None,
    valType: Formats.FormatType,
    offsetResetStrategy: OffsetResetStrategy = OffsetResetStrategy.EARLIEST,
    isolationLevel: ReadIsolationLevel = ReadUncommitted,
    rateLimit: Option[Int] = None,
    batchSize: Option[Int] = None,
    autoCommit: Boolean = true,
    aclCredentials: Option[AclCredentials] = None,
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
      ReadIsolationLevel,
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
    isolationLevel = t._8,
    rateLimit = t._9,
    batchSize = t._10,
    autoCommit = t._11
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
      isolationLevel: ReadIsolationLevel,
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
      isolationLevel = isolationLevel,
      rateLimit = rateLimit,
      batchSize = batchSize,
      autoCommit = autoCommit
    )
  // scalastyle:on
}

/**
 * Encodes configuration params for an inbound WebSocket stream.
 *
 * @param producerId
 *   the clientId to use for the Kafka producer.
 * @param instanceId
 *   a unique identifier for the producer client instance. If sessions are
 *   enabled this parameter is required.
 * @param topic
 *   the Kafka topic to subscribe to.
 * @param socketPayload
 *   the type of payload to expect through the socket.
 * @param keyType
 *   optional type for the message keys in the topic.
 * @param valType
 *   the type for the message values in the topic.
 * @param aclCredentials
 *   the Kafka ACL credentials to use with the Kafka client
 * @param bearerToken
 *   if present, contains the auth bearer token
 */
case class InSocketArgs(
    producerId: WsProducerId,
    topic: TopicName,
    socketPayload: SocketPayload,
    instanceId: Option[WsProducerInstanceId] = None,
    keyType: Option[Formats.FormatType] = None,
    valType: Formats.FormatType = Formats.StringType,
    aclCredentials: Option[AclCredentials] = None,
    bearerToken: Option[OAuth2BearerToken] = None
) extends SocketArgs {

  def withAclCredentials(aclCredentials: Option[AclCredentials]): InSocketArgs =
    copy(aclCredentials = aclCredentials)

  def withBearerToken(token: Option[OAuth2BearerToken]): InSocketArgs =
    copy(bearerToken = token)
}

object InSocketArgs {

  type TupledQueryParams = (
      WsProducerId,
      Option[WsProducerInstanceId],
      TopicName,
      SocketPayload,
      Option[FormatType],
      FormatType
  )

  def fromTupledQueryParams(
      t: TupledQueryParams
  ): InSocketArgs = fromQueryParams(
    clientId = t._1,
    instanceId = t._2,
    topicName = t._3,
    socketPayload = t._4,
    keyTpe = t._5,
    valTpe = t._6
  )

  def fromQueryParams(
      clientId: WsProducerId,
      instanceId: Option[WsProducerInstanceId],
      topicName: TopicName,
      socketPayload: SocketPayload,
      keyTpe: Option[FormatType],
      valTpe: FormatType
  ): InSocketArgs =
    InSocketArgs(
      producerId = clientId,
      instanceId = instanceId,
      topic = topicName,
      socketPayload = socketPayload,
      keyType = keyTpe,
      valType = valTpe
    )

}
