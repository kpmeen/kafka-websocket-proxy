package net.scalytica.kafka.wsproxy.models

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
 * @param keyType optional type for the message keys in the topic.
 * @param valType the type for the message values in the topic.
 * @param offsetResetStrategy the offset strategy to use. Defaults to EARLIEST
 * @param rateLimit max number of messages per second to pass through.
 * @param batchSize the number messages to include per batch.
 * @param autoCommit enable kafka consumer auto-commit interval.
 */
case class OutSocketArgs(
    clientId: String,
    groupId: Option[String],
    topic: String,
    keyType: Option[Formats.FormatType] = None,
    valType: Formats.FormatType = Formats.StringType,
    offsetResetStrategy: OffsetResetStrategy = OffsetResetStrategy.EARLIEST,
    rateLimit: Option[Int] = None,
    batchSize: Option[Int] = None,
    autoCommit: Boolean = true
)

object OutSocketArgs {

  // scalastyle:off
  def fromQueryParams(
      clientId: String,
      groupId: Option[String],
      topicName: String,
      keyTpe: Option[Formats.FormatType],
      valTpe: Formats.FormatType,
      offsetResetStrategy: OffsetResetStrategy,
      rateLimit: Option[Int],
      batchSize: Option[Int],
      autoCommit: Boolean
  ): OutSocketArgs = OutSocketArgs(
    clientId = clientId,
    groupId = groupId,
    topic = topicName,
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
 * @param keyType optional type for the message keys in the topic.
 * @param valType the type for the message values in the topic.
 */
case class InSocketArgs(
    topic: String,
    keyType: Option[Formats.FormatType] = None,
    valType: Formats.FormatType = Formats.StringType
)

object InSocketArgs {

  def fromOptQueryParams(
      t: Option[String],
      kt: Option[FormatType],
      vt: Option[FormatType]
  ): Option[InSocketArgs] =
    t.map(t => InSocketArgs(t, kt, vt.getOrElse(Formats.StringType)))

  def fromQueryParams(
      t: String,
      kt: Option[FormatType],
      vt: FormatType
  ): InSocketArgs = InSocketArgs(t, kt, vt)

}
