package net.scalytica.kafka.wsproxy.models

import org.apache.pekko.kafka.ProducerMessage
import net.scalytica.kafka.wsproxy.errors.ImpossibleError
import net.scalytica.kafka.wsproxy.logging.WithProxyLogger

/**
 * Type that wraps metadata about messages committed to Kafka.
 *
 * @param topic
 *   the topic the message was written to.
 * @param partition
 *   the partition the message was written to.
 * @param offset
 *   the partition offset for the written message.
 * @param timestamp
 *   the timestamp the message was received by Kafka.
 * @param clientMessageId
 *   message identifier given by the client to uniquely identify the message.
 */
case class WsProducerResult(
    topic: String,
    partition: Int = 0,
    offset: Long = 0,
    timestamp: Long = 0,
    clientMessageId: Option[String] = None
)

object WsProducerResult extends WithProxyLogger {

  private[this] def fromResult[K, V, PassThrough](
      res: ProducerMessage.Results[K, V, PassThrough]
  )(
      clientMessageId: PassThrough => Option[String]
  ): Seq[WsProducerResult] = {
    res match {
      case ProducerMessage.Result(md, ProducerMessage.Message(_, pt)) =>
        log.trace(
          "Mapping ProducerMessage.Result to a single WsProducerResult instance"
        )
        val pr = WsProducerResult(
          topic = md.topic,
          partition = md.partition,
          offset = md.offset,
          timestamp = md.timestamp,
          clientMessageId = clientMessageId(pt)
        )
        Seq(pr)

      case ProducerMessage.MultiResult(_, _) =>
        // This should not happen, since the WsProducer does not use the
        // ProducerMessage.MultiMessage type when publishing messages to Kafka.
        val msg =
          "The proxy does not currently support ProducerMessage.MultiResult."
        log.error(msg)
        throw ImpossibleError(msg)

      case ProducerMessage.PassThroughResult(_) =>
        log.trace(
          "Mapping ProducerMessage.PassThroughResult to WsProducerResult " +
            "will be ignored."
        )
        Seq.empty
    }
  }

  /**
   * Function to transform an instance of
   * [[org.apache.pekko.kafka.ProducerMessage.Results]] to a collection of
   * [[WsProducerResult]] instances.
   *
   * @param res
   *   The Kafka producer result to transform
   * @tparam K
   *   The type of the key element of the Kafka producer result
   * @tparam V
   *   The type of the value element of the Kafka producer result
   * @return
   *   a collection containing one-to-one transformations of kafka producer
   *   results to [[WsProducerResult]]s
   */
  def fromProducerResult[K, V](
      res: ProducerMessage.Results[K, V, WsProducerRecord[K, V]]
  ): Seq[WsProducerResult] = fromResult(res)(pt => pt.clientMessageId)

}
