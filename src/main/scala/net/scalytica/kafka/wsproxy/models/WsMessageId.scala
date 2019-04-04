package net.scalytica.kafka.wsproxy.models

/**
 * Identifies a consumed message by concatenating information found in the Kafka
 * consumer record.
 *
 * @param value the String which uniquely identifies the a consumed message.
 */
case class WsMessageId private (value: String)

object WsMessageId {

  def apply(
      topic: String,
      partition: Int,
      offset: Long,
      timestamp: Long
  ): WsMessageId = WsMessageId(s"$topic-$partition-$offset-$timestamp")

}
