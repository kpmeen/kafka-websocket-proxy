package net.scalytica.kafka.wsproxy.models

import akka.Done
import akka.kafka.ConsumerMessage

import net.scalytica.kafka.wsproxy.models.ValueDetails.OutValueDetails

import scala.concurrent.Future

/**
 * ADT describing any record that can be sent out from the service through the
 * WebSocket connection. Basically there are two types of messages; those with
 * a defined key of type {{{K}}} _and_ a value of type {{{V}}}. And records that
 * only contain a value of type {{{V}}}.
 *
 * @param key               The [[OutValueDetails]] describing the message key
 * @param value             The [[OutValueDetails]] describing the message value
 * @tparam K the type of the key
 * @tparam V the type of the value
 */
sealed abstract class WsConsumerRecord[+K, +V](
    key: Option[OutValueDetails[K]],
    value: OutValueDetails[V]
) {

  val topic: String
  val partition: Int
  val offset: Long
  val timestamp: Long
  val committableOffset: Option[ConsumerMessage.CommittableOffset]

  lazy val wsProxyMessageId: WsMessageId =
    WsMessageId(topic, partition, offset, timestamp)

  def commit(): Future[Done] =
    committableOffset.map(_.commitScaladsl()).getOrElse(Future.successful(Done))

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
    topic: String,
    partition: Int,
    offset: Long,
    timestamp: Long,
    key: OutValueDetails[K],
    value: OutValueDetails[V],
    committableOffset: Option[ConsumerMessage.CommittableOffset]
) extends WsConsumerRecord[K, V](Some(key), value)

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
    topic: String,
    partition: Int,
    offset: Long,
    timestamp: Long,
    value: OutValueDetails[V],
    committableOffset: Option[ConsumerMessage.CommittableOffset]
) extends WsConsumerRecord[Nothing, V](None, value)
