package net.scalytica.kafka.wsproxy.records

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

/**
 * Producer record type with key and value.
 *
 * @param key   The [[InValueDetails]] describing the message key
 * @param value The [[InValueDetails]] describing the message value
 * @tparam K the type of the key
 * @tparam V the type of the value
 */
case class ProducerKeyValueRecord[K, V](
    key: InValueDetails[K],
    value: InValueDetails[V]
) extends WsProducerRecord[K, V] {
  override val maybeKey = Some(key)
  override def isEmpty  = false
}

/**
 * Producer record type with value only.
 *
 * @param value The [[InValueDetails]] describing the message value
 * @tparam V the type of the value
 */
case class ProducerValueRecord[V](
    value: InValueDetails[V]
) extends WsProducerRecord[Nothing, V] {
  override val maybeKey = None
  override def isEmpty  = false
}

case object ProducerEmtpyMessage extends WsProducerRecord[Nothing, Nothing] {
  override val maybeKey = None
  override def value = throw new NoSuchElementException(
    "Trying to access value of empty message."
  )
  override def isEmpty = true
}
