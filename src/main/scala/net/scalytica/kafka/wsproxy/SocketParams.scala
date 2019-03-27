package net.scalytica.kafka.wsproxy

import net.scalytica.kafka.wsproxy.Formats.FormatType
import org.apache.kafka.clients.consumer.OffsetResetStrategy

import scala.util.Try

/**
 * Wrapper class for query parameters describing properties for both inbound and
 * outbound traffic through the WebSocket.
 *
 * @param out parameters describing the props for the outbound socket.
 * @param in optional parameters describing the props for the inbound socket.
 */
case class SocketParams(
    out: OutSocketParams,
    in: Option[InSocketParams] = None
)

/**
 * Encodes configuration params for an outbound WebSocket stream.
 *
 * @param clientId the clientId to use for the Kafka consumer.
 * @param groupId the groupId to use for the Kafka consumer.
 * @param topic the Kafka topic to subscribe to.
 * @param keyType optional type for the message keys in the topic.
 * @param valType the type for the message values in the topic.
 */
case class OutSocketParams(
    clientId: String,
    groupId: Option[String],
    topic: String,
    keyType: Option[Formats.FormatType] = None,
    valType: Formats.FormatType = Formats.String,
    offsetResetStrategy: OffsetResetStrategy
)

object OutSocketParams {

  def fromQueryParams(
      cid: String,
      gid: Option[String],
      t: String,
      kt: Option[String],
      vt: String,
      ors: String
  ): OutSocketParams = {
    val keyTpe = kt.flatMap(FormatType.fromString)
    val valTpe = FormatType.unsafeFromString(vt)
    val offReset = Try(OffsetResetStrategy.valueOf(ors.toUpperCase)).toOption
      .getOrElse(OffsetResetStrategy.EARLIEST)
    OutSocketParams(cid, gid, t, keyTpe, valTpe, offReset)
  }

}

/**
 * Encodes configuration params for an inbound WebSocket stream.
 *
 * @param topic the Kafka topic to subscribe to.
 * @param keyType optional type for the message keys in the topic.
 * @param valType the type for the message values in the topic.
 */
case class InSocketParams(
    topic: String,
    keyType: Option[Formats.FormatType] = None,
    valType: Formats.FormatType = Formats.String
)

object InSocketParams {

  def fromOptQueryParams(
      t: Option[String],
      kt: Option[String],
      vt: Option[String]
  ): Option[InSocketParams] = {
    t.map { t =>
      val keyTpe = kt.flatMap(FormatType.fromString)
      val valTpe = vt.flatMap(FormatType.fromString).getOrElse(Formats.String)
      InSocketParams(t, keyTpe, valTpe)
    }
  }

  def fromQueryParams(
      t: String,
      kt: Option[String],
      vt: String
  ): InSocketParams = {
    val keyTpe = kt.flatMap(FormatType.fromString)
    val valTpe = FormatType.unsafeFromString(vt)
    InSocketParams(t, keyTpe, valTpe)
  }

}
