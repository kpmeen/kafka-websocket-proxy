package net.scalytica.kafka.wsproxy.consumer

import akka.actor.ActorSystem
import akka.kafka.{ConsumerSettings, Subscriptions}
import akka.kafka.scaladsl.Consumer
import akka.stream.scaladsl.Source
import com.typesafe.scalalogging.Logger
import net.scalytica.kafka.wsproxy.ConsumerInterceptorClass
import net.scalytica.kafka.wsproxy.records.{
  ConsumerKeyValueRecord,
  ConsumerValueRecord,
  OutValueDetails,
  WsConsumerRecord
}
import org.apache.kafka.clients.consumer.ConsumerConfig._
import org.apache.kafka.clients.consumer.{ConsumerRecord, OffsetResetStrategy}
import org.apache.kafka.clients.consumer.OffsetResetStrategy.EARLIEST
import org.apache.kafka.common.serialization.Deserializer

import scala.concurrent.duration._

object WsConsumer {

  private[this] val logger = Logger(getClass)

  private[this] val kafkaUrl = "localhost:29092"

  private[this] def baseConsnumerSettings[K, V](
      id: String,
      gid: Option[String],
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
        INTERCEPTOR_CLASSES_CONFIG     -> ConsumerInterceptorClass,
        AUTO_OFFSET_RESET_CONFIG       -> offsetReset.name.toLowerCase,
        ENABLE_AUTO_COMMIT_CONFIG      -> "true",
        AUTO_COMMIT_INTERVAL_MS_CONFIG -> s"${50.millis.toMillis}"
      )
      .withClientId(s"$id-client")
      .withGroupId(gid.getOrElse(s"$id-group"))

  private[this] def logMessage[K, V](record: ConsumerRecord[K, V]): String = {
    val offset    = record.offset
    val partition = record.partition()
    val ts        = record.timestamp()
    val topic     = record.topic()
    f"topic: $topic%-20s, partition: $partition%-4s, offset: $offset%-12s, " +
      f"timestamp: $ts%-20s"
  }

  /**
   * Creates an akka-streams based Kafka Source for messages where the keys are
   * of type [[K]] and values of type [[V]].
   *
   * @param topic the topic to subscribe to.
   * @param clientId the clientId to give the Kafka consumer
   * @param groupId the groupId to start a socket for.
   * @param as an implicit ActorSystem
   * @param ks the Deserializer to use for the message key
   * @param vs the Deserializer to use for the message value
   * @tparam K the type of the message key
   * @tparam V the type of the message value
   *
   * @return a [[Source]] containing [[WsConsumerRecord]]s.
   */
  def kafkaSource[K, V](
      topic: String,
      clientId: String,
      groupId: Option[String]
  )(
      implicit
      as: ActorSystem,
      ks: Deserializer[K],
      vs: Deserializer[V]
  ): Source[WsConsumerRecord[K, V], Consumer.Control] = {
    val settings     = baseConsnumerSettings[K, V](id = clientId, gid = groupId)
    val subscription = Subscriptions.topics(Set(topic))
    Consumer
    // FIXME: The current setup, with auto commit of offsets, seems to lose
    //        messages if they are read just after the connection is dropped
    //        by the client. So it's likely that the commit will have to be
    //        done manually, at a safe place, somewhere in the flow. One good
    //        candidate is to allow for a final commit in the termination
    //        handler. 🤔
      .committableSource[K, V](settings, subscription)
      .log("Consuming message", msg => logMessage(msg.record))
      .map { msg =>
        Option(msg.record.key)
          .map { key =>
            ConsumerKeyValueRecord[K, V](
              partition = msg.record.partition,
              offset = msg.record.offset,
              key = OutValueDetails[K](key),
              value = OutValueDetails[V](msg.record.value),
              msg.committableOffset
            )
          }
          .getOrElse {
            ConsumerValueRecord[V](
              partition = msg.record.partition,
              offset = msg.record.offset,
              value = OutValueDetails[V](msg.record.value),
              msg.committableOffset
            )
          }
      }
  }

}
