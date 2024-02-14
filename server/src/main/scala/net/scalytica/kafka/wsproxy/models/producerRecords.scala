package net.scalytica.kafka.wsproxy.models

import net.scalytica.kafka.wsproxy.models.ValueDetails.InValueDetails
import net.scalytica.kafka.wsproxy.producer.ExtendedProducerRecord
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.header.Header

import scala.jdk.CollectionConverters._

/**
 * ADT describing any record that can come in to the service through the
 * WebSocket connection. Basically there are two types of messages; those with a
 * defined key of type {{{K}}} _and_ a value of type {{{V}}}. And records that
 * only contain a value of type {{{V}}}.
 *
 * @tparam K
 *   the type of the key
 * @tparam V
 *   the type of the value
 */
sealed trait WsProducerRecord[+K, +V] {

  val clientMessageId: Option[String]
  val headers: Option[Seq[KafkaHeader]]

  def maybeKey: Option[InValueDetails[_ <: K]] = None
  def value: InValueDetails[_ <: V]

  def isEmpty: Boolean
  def nonEmpty: Boolean = !isEmpty

}

object WsProducerRecord {

  /**
   * Converts a [[WsProducerRecord]] into a Kafka [[ProducerRecord]].
   *
   * @param topic
   *   the topic name the record is to be written to.
   * @param msg
   *   the message to send to the Kafka topic.
   * @tparam K
   *   the message key type
   * @tparam V
   *   the message value type
   * @return
   *   an instance of [[ProducerRecord]]
   */
  @throws[IllegalStateException]
  def asKafkaProducerRecord[K, V](
      topic: TopicName,
      msg: WsProducerRecord[K, V]
  ): ProducerRecord[K, V] = {
    val headers: Iterable[Header] =
      msg.headers.getOrElse(Seq.empty).map(_.asRecordHeader)

    msg match {
      case kvm: ProducerKeyValueRecord[K, V] =>
        new ExtendedProducerRecord[K, V](
          topic.value,
          kvm.key.value,
          kvm.value.value,
          headers.asJava
        )

      case vm: ProducerValueRecord[V] =>
        new ExtendedProducerRecord[K, V](
          topic.value,
          vm.value.value,
          headers.asJava
        )

      case ProducerEmptyMessage =>
        throw new IllegalStateException(
          "EmptyMessage passed through stream pipeline, but should have" +
            " been filtered out."
        )
    }
  }

}

/**
 * Producer record type with key and value.
 *
 * @param key
 *   The [[InValueDetails]] describing the message key
 * @param value
 *   The [[InValueDetails]] describing the message value
 * @param headers
 *   Optional [[KafkaHeader]] s to use for the Kafka message
 * @param clientMessageId
 *   Message identifier given by the client to uniquely identify the message.
 * @tparam K
 *   the type of the key
 * @tparam V
 *   the type of the value
 */
case class ProducerKeyValueRecord[K, V](
    key: InValueDetails[K],
    value: InValueDetails[V],
    headers: Option[Seq[KafkaHeader]],
    clientMessageId: Option[String]
) extends WsProducerRecord[K, V] {
  override val maybeKey: Option[InValueDetails[K]] = Some(key)
  override def isEmpty: Boolean                    = false
}

/**
 * Producer record type with value only.
 *
 * @param value
 *   The [[InValueDetails]] describing the message value
 * @param headers
 *   Optional [[KafkaHeader]] s to use for the Kafka message
 * @param clientMessageId
 *   Message identifier given by the client to uniquely identify the message.
 * @tparam V
 *   the type of the value
 */
case class ProducerValueRecord[V](
    value: InValueDetails[V],
    headers: Option[Seq[KafkaHeader]],
    clientMessageId: Option[String]
) extends WsProducerRecord[Nothing, V] {
  override val maybeKey: Option[Nothing] = None
  override def isEmpty: Boolean          = false
}

case object ProducerEmptyMessage extends WsProducerRecord[Nothing, Nothing] {
  override val maybeKey: Option[Nothing]        = None
  override val clientMessageId: Option[Nothing] = None
  override val headers: Option[Nothing]         = None

  override def value: InValueDetails[_ <: Nothing] =
    throw new NoSuchElementException(
      "Trying to access value of empty message."
    )
  override def isEmpty = true
}
