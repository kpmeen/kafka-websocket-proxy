package net.scalytica.kafka.wsproxy.models

import net.scalytica.kafka.wsproxy.avro.SchemaTypes.AvroProducerRecord
import net.scalytica.kafka.wsproxy.models.ValueDetails.InValueDetails

/**
 * ADT describing any record that can come in to the service through the
 * WebSocket connection. Basically there are two types of messages; those with
 * a defined key of type {{{K}}} _and_ a value of type {{{V}}}. And records that
 * only contain a value of type {{{V}}}.
 *
 * @tparam K the type of the key
 * @tparam V the type of the value
 */
sealed trait WsProducerRecord[+K, +V] {

  def maybeKey: Option[InValueDetails[_ <: K]] = None
  def value: InValueDetails[_ <: V]

  def isEmpty: Boolean
  def nonEmpty: Boolean = !isEmpty

}

object WsProducerRecord {

  def fromAvro(
      avro: AvroProducerRecord
  ): WsProducerRecord[Array[Byte], Array[Byte]] = {
    avro.key
      .map { k =>
        ProducerKeyValueRecord[Array[Byte], Array[Byte]](
          key = InValueDetails(k, Formats.AvroType),
          value = InValueDetails(avro.value, Formats.AvroType),
          headers = avro.headers.map(_.map(KafkaHeader.fromAvro))
        )
      }
      .getOrElse {
        ProducerValueRecord[Array[Byte]](
          value = InValueDetails(avro.value, Formats.AvroType),
          headers = avro.headers.map(_.map(KafkaHeader.fromAvro))
        )
      }
  }

}

/**
 * Producer record type with key and value.
 *
 * @param key   The [[InValueDetails]] describing the message key
 * @param value The [[InValueDetails]] describing the message value
 * @param headers Optional [[KafkaHeader]]s to use for the Kafka message
 * @tparam K the type of the key
 * @tparam V the type of the value
 */
case class ProducerKeyValueRecord[K, V](
    key: InValueDetails[K],
    value: InValueDetails[V],
    headers: Option[Seq[KafkaHeader]]
) extends WsProducerRecord[K, V] {
  override val maybeKey = Some(key)
  override def isEmpty  = false
}

/**
 * Producer record type with value only.
 *
 * @param value The [[InValueDetails]] describing the message value
 * @param headers Optional [[KafkaHeader]]s to use for the Kafka message
 * @tparam V the type of the value
 */
case class ProducerValueRecord[V](
    value: InValueDetails[V],
    headers: Option[Seq[KafkaHeader]]
) extends WsProducerRecord[Nothing, V] {
  override val maybeKey = None
  override def isEmpty  = false
}

case object ProducerEmptyMessage extends WsProducerRecord[Nothing, Nothing] {
  override val maybeKey = None

  override def value = throw new NoSuchElementException(
    "Trying to access value of empty message."
  )
  override def isEmpty = true
}
