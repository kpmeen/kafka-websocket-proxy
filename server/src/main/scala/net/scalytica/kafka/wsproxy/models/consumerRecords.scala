package net.scalytica.kafka.wsproxy.models

import akka.Done
import akka.kafka.ConsumerMessage
import net.scalytica.kafka.wsproxy.avro.SchemaTypes.AvroConsumerRecord
import net.scalytica.kafka.wsproxy.models.ValueDetails.OutValueDetails
import org.apache.kafka.common.serialization.Serializer

import scala.concurrent.Future

/**
 * ADT describing any record that can be sent out from the service through the
 * WebSocket connection. Basically there are two types of messages; those with
 * a defined key of type {{{K}}} _and_ a value of type {{{V}}}. And records that
 * only contain a value of type {{{V}}}.
 *
 * @param key   The [[OutValueDetails]] describing the message key
 * @param value The [[OutValueDetails]] describing the message value
 * @tparam K the type of the key
 * @tparam V the type of the value
 */
sealed abstract class WsConsumerRecord[+K, +V](
    key: Option[OutValueDetails[K]],
    value: OutValueDetails[V]
) {

  val topic: TopicName
  val partition: Partition
  val offset: Offset
  val timestamp: Timestamp
  val committableOffset: Option[ConsumerMessage.CommittableOffset]

  lazy val wsProxyMessageId: WsMessageId =
    WsMessageId(topic, partition, offset, timestamp)

  def commit(): Future[Done] =
    committableOffset.map(_.commitScaladsl()).getOrElse(Future.successful(Done))

  def toAvroRecord[Key >: K, Value >: V](
      implicit
      keySer: Serializer[Key],
      valSer: Serializer[Value]
  ): AvroConsumerRecord = {
    AvroConsumerRecord(
      wsProxyMessageId = wsProxyMessageId.value,
      topic = topic.value,
      partition = partition.value,
      offset = offset.value,
      timestamp = timestamp.value,
      key = key.map(ovd => keySer.serialize(topic.value, ovd.value)),
      value = valSer.serialize(topic.value, value.value)
    )
  }

  def withKeyFormatType(keyType: Formats.FormatType): WsConsumerRecord[K, V]
  def withValueFormatType(valType: Formats.FormatType): WsConsumerRecord[K, V]
}

object WsConsumerRecord {

  def fromAvro(
      avro: AvroConsumerRecord
  ): WsConsumerRecord[Array[Byte], Array[Byte]] = {
    avro.key match {
      case Some(key) =>
        ConsumerKeyValueRecord(
          topic = TopicName(avro.topic),
          partition = Partition(avro.partition),
          offset = Offset(avro.offset),
          timestamp = Timestamp(avro.timestamp),
          key = OutValueDetails(key, Option(Formats.AvroType)),
          value = OutValueDetails(avro.value, Option(Formats.AvroType)),
          committableOffset = None
        )

      case None =>
        ConsumerValueRecord(
          topic = TopicName(avro.topic),
          partition = Partition(avro.partition),
          offset = Offset(avro.offset),
          timestamp = Timestamp(avro.timestamp),
          value = OutValueDetails(avro.value, Option(Formats.AvroType)),
          committableOffset = None
        )
    }
  }

}

/**
 * Consumer record type with key and value.
 *
 * @param topic             The topic name the message was consumed from
 * @param partition         The topic partition for the message
 * @param offset            The topic offset of the message
 * @param timestamp         The timestamp for when Kafka received the record
 * @param key               The [[OutValueDetails]] describing the message key
 * @param value             The [[OutValueDetails]] describing the message value
 * @param committableOffset An optional handle to the mechanism that allows
 *                          committing the message offset back to Kafka.
 * @tparam K the type of the key
 * @tparam V the type of the value
 */
case class ConsumerKeyValueRecord[K, V](
    topic: TopicName,
    partition: Partition,
    offset: Offset,
    timestamp: Timestamp,
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
 * @param topic             The topic name the message was consumed from
 * @param partition         The topic partition for the message
 * @param offset            The topic offset of the message
 * @param timestamp         The timestamp for when Kafka received the record
 * @param value             The [[OutValueDetails]] describing the message value
 * @param committableOffset An optional handle to the mechanism that allows
 *                          committing the message offset back to Kafka.
 * @tparam V the type of the value
 */
case class ConsumerValueRecord[V](
    topic: TopicName,
    partition: Partition,
    offset: Offset,
    timestamp: Timestamp,
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
