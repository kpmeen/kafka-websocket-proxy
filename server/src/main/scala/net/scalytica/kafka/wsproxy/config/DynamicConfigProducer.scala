package net.scalytica.kafka.wsproxy.config

import akka.Done
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.adapter._
import akka.kafka.ProducerSettings
import net.scalytica.kafka.wsproxy._
import net.scalytica.kafka.wsproxy.codecs.{BasicSerdes, DynamicCfgSerde}
import net.scalytica.kafka.wsproxy.config.Configuration.{AppCfg, DynamicCfg}
import net.scalytica.kafka.wsproxy.logging.WithProxyLogger
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord}

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success}

/**
 * Producer implementation for producing [[DynamicCfg]] messages to Kafka.
 *
 * @param cfg
 *   The [[AppCfg]] to use.
 * @param sys
 *   The typed [[ActorSystem]] to use.
 */
private[config] class DynamicConfigProducer(
    implicit cfg: AppCfg,
    sys: ActorSystem[_]
) extends WithProxyLogger {

  private[this] val kSer = BasicSerdes.StringSerializer
  private[this] val vSer = new DynamicCfgSerde().serializer()

  private[this] val kafkaUrl    = cfg.kafkaClient.bootstrapHosts.mkString()
  private[this] val dynCfgTopic = cfg.dynamicConfigHandler.topicName.value

  private[this] lazy val producerProps =
    ProducerSettings(sys.toClassic, Some(kSer), Some(vSer))
      .withBootstrapServers(kafkaUrl)
      .withProducerFactory(initialiseProducer)

  /**
   * Helper function to initialise a Kafka Producer with the correct settings.
   *
   * @param ps
   *   The [[ProducerSettings]] to use.
   * @return
   *   an instance of [[KafkaProducer]]
   */
  private[this] def initialiseProducer(
      ps: ProducerSettings[String, DynamicCfg]
  ): KafkaProducer[String, DynamicCfg] = {
    val props = cfg.producer.kafkaClientProperties ++
      ps.getProperties.asScala.toMap ++
      producerMetricsProperties

    log.trace(s"Using producer configuration:\n${props.mkString("\n")}")

    new KafkaProducer[String, DynamicCfg](
      props,
      ps.keySerializerOpt.orNull,
      ps.valueSerializerOpt.orNull
    )
  }

  private[this] lazy val producer = producerProps.createKafkaProducer()

  /**
   * Function that performs the send to Kafka.
   *
   * @param key
   *   The key to use for the data being sent to the Kafka topic.
   * @param dynCfg
   *   An optional value. If the value is {{{None}}}, a tombstone is sent to the
   *   topic.
   * @param ec
   *   The [[ExecutionContext]] to use
   * @return
   *   Eventually returns a [[Done]].
   */
  private[this] def publish(
      key: String,
      dynCfg: Option[DynamicCfg]
  )(implicit ec: ExecutionContext): Future[Done] = {
    val record =
      new ProducerRecord[String, DynamicCfg](
        dynCfgTopic,
        key,
        dynCfg.orNull
      )

    val res = producer.send(record).toScalaFuture

    res.onComplete {
      case Success(rm) =>
        log.debug(
          s"Successfully sent DynamicCfg record with key $key to Kafka. [" +
            s"topic: ${rm.topic()}," +
            s"partition: ${rm.partition()}," +
            s"offset: ${rm.offset()}" +
            "]"
        )
        log.trace(
          "DynamicCfg data written was: " +
            dynCfg.map(_.asHoconString()).getOrElse("<tombstone>")
        )

      case Failure(ex) =>
        log.error(
          s"Failed to send DynamicCfg record with key $key to Kafka",
          ex
        )
    }

    res.map(_ => Done)
  }

  /**
   * Method for publishing new or updated DynamicCfg messages to the Kafka
   * topic.
   *
   * @param dynCfg
   *   The [[DynamicCfg]] to push to Kafka
   * @param ec
   *   The execution context to use
   * @return
   *   Returns a [[Future]] of [[Done]].
   */
  def publishConfig(
      dynCfg: DynamicCfg
  )(implicit ec: ExecutionContext): Future[Done] = {
    dynamicCfgTopicKey(dynCfg)
      .map { key =>
        publish(key, Option(dynCfg))
      }
      .getOrElse {
        log.warn("DynamicCfg was rejected because no key was provided.")
        Future.successful(Done)
      }
  }

  /**
   * Method for publishing a tombstone for the DynamicCfg associated with the
   * given key to the Kafka topic.
   *
   * @param key
   *   The [[String]] key associated with the [[DynamicCfg]] to remove.
   * @param ec
   *   The execution context to use
   * @return
   *   Returns a [[Future]] of [[Done]].
   */
  def removeConfig(key: String)(implicit ec: ExecutionContext): Future[Done] = {
    Option(key).map(k => publish(k, None)).getOrElse {
      log.warn("DynamicCfg was rejected because no key was provided.")
      Future.successful(Done)
    }
  }
}
