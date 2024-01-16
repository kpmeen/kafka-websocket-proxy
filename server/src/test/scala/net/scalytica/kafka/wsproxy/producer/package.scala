package net.scalytica.kafka.wsproxy

import org.apache.pekko.NotUsed
import org.apache.pekko.kafka.ProducerMessage.Envelope
import org.apache.pekko.stream.scaladsl.Flow
import net.scalytica.kafka.wsproxy.models.{WsProducerRecord, WsProducerResult}

package object producer {

  type ProducerRecord = WsProducerRecord[String, String]
  type ProducerEnvelope =
    Envelope[String, String, ProducerRecord]
  type ProducerFlow =
    Flow[ProducerEnvelope, WsProducerResult, NotUsed]

}
