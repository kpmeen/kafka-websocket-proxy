package net.scalytica.kafka.wsproxy.producer

import akka.NotUsed
import akka.actor.ActorSystem
import akka.kafka.{ProducerMessage, ProducerSettings}
import akka.kafka.scaladsl.Producer
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Sink}
import com.typesafe.scalalogging.Logger
import net.scalytica.kafka.wsproxy.InterceptorClass
import org.apache.kafka.clients.producer.ProducerConfig._
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.Serializer

object WsProducer {

  private[this] val logger = Logger(getClass)

  private[this] val kafkaUrl = "localhost:29092"

  protected def baseProducerSettings[K, V](
      implicit
      as: ActorSystem,
      ks: Serializer[K],
      vs: Serializer[V]
  ) =
    ProducerSettings(as, ks, vs)
      .withBootstrapServers(kafkaUrl)
      .withProperties(
        // Enables stream monitoring in confluent control center
        // scalastyle:off
        INTERCEPTOR_CLASSES_CONFIG -> InterceptorClass
        // scalastyle:on
      )

  def kafkaSink[K, V](topic: String)(
      implicit
      as: ActorSystem,
      mat: ActorMaterializer,
      ks: Serializer[K],
      vs: Serializer[V]
  ): Sink[(K, V), NotUsed] = {
    Flow[(K, V)]
      .map {
        case (key, value) =>
          ProducerMessage.Message(
            new ProducerRecord[K, V](topic, key, value),
            key -> value
          )
      }
      .via(Producer.flexiFlow(baseProducerSettings[K, V]))
      .map(_.passThrough)
      .to(Sink.foreach {
        case (key, value) =>
          logger.debug(s"Wrote key: $key value: $value to topic $topic")
      })
  }

}
