package net.scalytica.kafka.wsproxy.models

import akka.kafka.ProducerMessage
import net.scalytica.kafka.wsproxy.avro.SchemaTypes.AvroProducerResult
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
    partition: Int,
    offset: Long,
    timestamp: Long,
    clientMessageId: Option[String] = None
) {

  def toAvro: AvroProducerResult = {
    AvroProducerResult(
      topic = topic,
      partition = partition,
      offset = offset,
      timestamp = timestamp,
      clientMessageId = clientMessageId
    )
  }

}

object WsProducerResult extends WithProxyLogger {

  def fromProducerResult[K, V](
      res: ProducerMessage.Results[K, V, WsProducerRecord[K, V]]
  ): Seq[WsProducerResult] = {
    res match {
      case ProducerMessage.Result(md, ProducerMessage.Message(_, pt)) =>
        logger.trace(
          "Mapping ProducerMessage.Result to a single WsProducerResult instance"
        )
        val pr = WsProducerResult(
          topic = md.topic,
          partition = md.partition,
          offset = md.offset,
          timestamp = md.timestamp,
          clientMessageId = pt.clientMessageId
        )
        Seq(pr)

      case ProducerMessage.MultiResult(_, _) =>
        // This should not happen, since the WsProducer does not use the
        // ProducerMessage.MultiMessage type when publishing messages to Kafka.
        val msg =
          "The proxy does not currently support ProducerMessage.MultiResult."
        logger.error(msg)
        throw ImpossibleError(msg)

      case ProducerMessage.PassThroughResult(_) =>
        logger.trace(
          "Mapping ProducerMessage.PassThroughResult to WsProducerResult " +
            "will be ignored."
        )
        Seq.empty
    }
  }

}
