package net.scalytica.kafka.wsproxy.consumer

import akka.actor.ActorSystem
import akka.kafka.ConsumerMessage.CommittableMessage
import akka.kafka.{ConsumerSettings, Subscriptions}
import akka.kafka.scaladsl.Consumer
import akka.stream.scaladsl.Source
import com.typesafe.scalalogging.Logger
import net.scalytica.kafka.wsproxy.InterceptorClass
import org.apache.kafka.clients.consumer.ConsumerConfig._
import org.apache.kafka.clients.consumer.{ConsumerRecord, OffsetResetStrategy}
import org.apache.kafka.clients.consumer.OffsetResetStrategy.EARLIEST
import org.apache.kafka.common.serialization.Deserializer

object WsConsumer {

  private[this] val logger = Logger(getClass)

  private[this] val kafkaUrl = "localhost:29092"

  private[this] def baseConsnumerSettings[K, V](
      id: String,
      offsetReset: OffsetResetStrategy = EARLIEST
  )(
      implicit
      as: ActorSystem,
      ks: Deserializer[K],
      vs: Deserializer[V]
  ) =
    ConsumerSettings(as, ks, vs)
      .withBootstrapServers(kafkaUrl)
      .withProperties(
        // Enables stream monitoring in confluent control center
//        INTERCEPTOR_CLASSES_CONFIG -> InterceptorClass,
        AUTO_OFFSET_RESET_CONFIG -> offsetReset.name.toLowerCase
      )
      .withClientId(s"$id-client")
      .withGroupId(s"$id-group")

  private[this] def logMessage[K, V](msg: CommittableMessage[K, V]): String = {
    val offset    = msg.record.offset
    val partition = msg.record.partition()
    val ts        = msg.record.timestamp()
    val topic     = msg.record.topic()
    f"topic: $topic%-20s, partition: $partition%-4s, offset: $offset%-12s, " +
      f"timestamp: $ts%-20s"
  }

  def kafkaSource[K, V](
      topic: String,
      id: String
  )(
      implicit
      as: ActorSystem,
      ks: Deserializer[K],
      vs: Deserializer[V]
  ): Source[ConsumerRecord[K, V], Consumer.Control] = {
    val settings     = baseConsnumerSettings[K, V](id = id)
    val subscription = Subscriptions.topics(Set(topic))
    Consumer
      .committableSource[K, V](settings, subscription)
      .log("Incoming message", logMessage)
      .map(_.record)
  }

}
