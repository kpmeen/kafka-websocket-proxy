package net.scalytica.kafka.wsproxy

import akka.NotUsed
import akka.kafka.ProducerMessage.Envelope
import akka.stream.scaladsl.Flow
import net.scalytica.kafka.wsproxy.models.{WsProducerRecord, WsProducerResult}

package object producer {

  type ProducerRecord = WsProducerRecord[String, String]
  type ProducerEnvelope =
    Envelope[String, String, ProducerRecord]
  type ProducerFlow =
    Flow[ProducerEnvelope, WsProducerResult, NotUsed]

}
