package net.scalytica.kafka.wsproxy.models

import net.scalytica.kafka.wsproxy.models.ValueDetails.OutValueDetails

import org.apache.pekko.kafka.ConsumerMessage

/**
 * ADT describing any record that can be sent out from the service through the
 * WebSocket connection. Basically there are two types of messages; those with a
 * defined key of type {{{K}}} _and_ a value of type {{{V}}}. And records that
 * only contain a value of type {{{V}}}.
 *
 * @param maybeKey
 *   The [[OutValueDetails]] describing the message key
 * @param value
 *   The [[OutValueDetails]] describing the message value
 * @tparam K
 *   the type of the key
 * @tparam V
 *   the type of the value
 */
sealed abstract class WsConsumerRecord[+K, +V](
    maybeKey: Option[OutValueDetails[K]],
    value: OutValueDetails[V]
) {

  val topic: TopicName
  val partition: Partition
  val offset: Offset
  val timestamp: Timestamp
  val headers: Option[Seq[KafkaHeader]]
  val committableOffset: Option[ConsumerMessage.CommittableOffset]

  lazy val wsProxyMessageId: WsMessageId =
    WsMessageId(topic, partition, offset, timestamp)

  def withKeyFormatType(keyType: Formats.FormatType): WsConsumerRecord[K, V]
  def withValueFormatType(valType: Formats.FormatType): WsConsumerRecord[K, V]

  override def toString: String =
    "WsConsumerRecord(" +
      s"  maybeKey=${maybeKey.map(_.toString).getOrElse("")}" +
      s"  value=${value.toString}" +
      s"  topic=${topic.value}," +
      s"  partition=${partition.value}," +
      s"  timestamp=${timestamp.value}," +
      s"  headers=[${headers.map(_.mkString(", ")).getOrElse("")}]," +
      s"  committableOffset=${committableOffset.getOrElse("")}," +
      s"  wsProxyMessageId=$wsProxyMessageId" +
      ")"
}

/**
 * Consumer record type with key and value.
 *
 * @param topic
 *   The topic name the message was consumed from
 * @param partition
 *   The topic partition for the message
 * @param offset
 *   The topic offset of the message
 * @param timestamp
 *   The timestamp for when Kafka received the record
 * @param headers
 *   The [[KafkaHeader]] s found on the message.
 * @param maybeKey
 *   The [[OutValueDetails]] for the message key
 * @param value
 *   The [[OutValueDetails]] for the message value
 * @param committableOffset
 *   An optional handle to the mechanism that allows committing the message
 *   offset back to Kafka.
 * @tparam K
 *   the type of the key
 * @tparam V
 *   the type of the value
 */
case class ConsumerKeyValueRecord[K, V](
    topic: TopicName,
    partition: Partition,
    offset: Offset,
    timestamp: Timestamp,
    headers: Option[Seq[KafkaHeader]],
    key: OutValueDetails[K],
    value: OutValueDetails[V],
    committableOffset: Option[ConsumerMessage.CommittableOffset]
) extends WsConsumerRecord[K, V](Some(key), value) {

  def withKeyFormatType(keyType: Formats.FormatType): WsConsumerRecord[K, V] =
    copy(key = key.copy(format = Option(keyType)))

  def withValueFormatType(valType: Formats.FormatType): WsConsumerRecord[K, V] =
    copy(value = value.copy(format = Option(valType)))

}

/**
 * Consumer record type with value only.
 *
 * @param topic
 *   The topic name the message was consumed from
 * @param partition
 *   The topic partition for the message
 * @param offset
 *   The topic offset of the message
 * @param timestamp
 *   The timestamp for when Kafka received the record
 * @param headers
 *   The [[KafkaHeader]] s found on the message.
 * @param value
 *   The [[OutValueDetails]] for the message value
 * @param committableOffset
 *   An optional handle to the mechanism that allows committing the message
 *   offset back to Kafka.
 * @tparam V
 *   the type of the value
 */
case class ConsumerValueRecord[V](
    topic: TopicName,
    partition: Partition,
    offset: Offset,
    timestamp: Timestamp,
    headers: Option[Seq[KafkaHeader]],
    value: OutValueDetails[V],
    committableOffset: Option[ConsumerMessage.CommittableOffset]
) extends WsConsumerRecord[Nothing, V](None, value) {

  def withKeyFormatType(
      keyType: Formats.FormatType
  ): WsConsumerRecord[Nothing, V] = this

  def withValueFormatType(
      valType: Formats.FormatType
  ): WsConsumerRecord[Nothing, V] =
    copy(value = value.copy(format = Option(valType)))
}
