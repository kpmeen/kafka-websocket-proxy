package net.scalytica.kafka.wsproxy.models

import akka.kafka.ProducerMessage

/**
 * Type that wraps metadata about messages committed to Kafka.
 *
 * @param topic     the topic the message was written to.
 * @param partition the partition the message was written to.
 * @param offset    the partition offset for the written message.
 * @param timestamp the timestamp the message was received by Kafka.
 */
case class WsProducerResult(
    topic: String,
    partition: Int,
    offset: Long,
    timestamp: Long
)

object WsProducerResult {

  def fromProducerResults[K, V](
      res: ProducerMessage.Results[K, V, _]
  ): Seq[WsProducerResult] = {
    res match {
      case ProducerMessage.Result(md, ProducerMessage.Message(_, _)) =>
        val pr = WsProducerResult(
          topic = md.topic,
          partition = md.partition,
          offset = md.offset,
          timestamp = md.timestamp
        )
        Seq(pr)

      case ProducerMessage.MultiResult(p, _) =>
        p.map { r =>
          WsProducerResult(
            topic = r.metadata.topic,
            partition = r.metadata.partition,
            offset = r.metadata.offset,
            timestamp = r.metadata.timestamp
          )
        }

      case ProducerMessage.PassThroughResult(_) =>
        Seq.empty
    }
  }

}
