package net.scalytica.kafka.wsproxy.models

/**
 * Identifies a consumed message by concatenating information found in the Kafka
 * consumer record.
 *
 * @param value the String which uniquely identifies the a consumed message.
 */
case class WsMessageId private (value: String) extends AnyVal

object WsMessageId {

  def apply(
      topic: TopicName,
      partition: Partition,
      offset: Offset,
      timestamp: Timestamp
  ): WsMessageId =
    WsMessageId(
      s"${topic.value}-${partition.value}-${offset.value}-${timestamp.value}"
    )

}
