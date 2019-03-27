package net.scalytica.kafka.wsproxy.records
import akka.Done
import akka.kafka.ConsumerMessage

import scala.concurrent.Future

/**
 * ADT describing any record that can be sent out from the service through the
 * WebSocket connection. Basically there are two types of messages; those with
 * a defined key of type {{{K}}} _and_ a value of type {{{V}}}. And records that
 * only contain a value of type {{{V}}}.
 *
 * @param partition The topic partition for the message
 * @param offset    The topic offset of the message
 * @param key       The [[OutValueDetails]] describing the message key
 * @param value     The [[OutValueDetails]] describing the message value
 * @param committableOffset A handle to the mechanism that allows committing the
 *                          message offset back to Kafka
 * @tparam K the type of the key
 * @tparam V the type of the value
 */
sealed abstract class WsConsumerRecord[+K, +V](
    partition: Int,
    offset: Long,
    key: Option[OutValueDetails[K]],
    value: OutValueDetails[V],
    committableOffset: ConsumerMessage.CommittableOffset
) {

  def commit(): Future[Done] = committableOffset.commitScaladsl()

}

/**
 * Consumer record type with key and value.
 *
 * @param partition The topic partition for the message
 * @param offset    The topic offset of the message
 * @param key       The [[OutValueDetails]] describing the message key
 * @param value     The [[OutValueDetails]] describing the message value
 * @param committableOffset A handle to the mechanism that allows committing the
 *                          message offset back to Kafka
 * @tparam K the type of the key
 * @tparam V the type of the value
 */
case class ConsumerKeyValueRecord[K, V](
    partition: Int,
    offset: Long,
    key: OutValueDetails[K],
    value: OutValueDetails[V],
    committableOffset: ConsumerMessage.CommittableOffset
) extends WsConsumerRecord[K, V](
      partition,
      offset,
      Some(key),
      value,
      committableOffset
    )

/**
 * Consumer record type with value only.
 *
 * @param partition The topic partition for the message
 * @param offset    The topic offset of the message
 * @param value     The [[OutValueDetails]] describing the message value
 * @param committableOffset A handle to the mechanism that allows committing the
 *                          message offset back to Kafka
 * @tparam V the type of the value
 */
case class ConsumerValueRecord[V](
    partition: Int,
    offset: Long,
    value: OutValueDetails[V],
    committableOffset: ConsumerMessage.CommittableOffset
) extends WsConsumerRecord[Nothing, V](
      partition,
      offset,
      None,
      value,
      committableOffset
    )
