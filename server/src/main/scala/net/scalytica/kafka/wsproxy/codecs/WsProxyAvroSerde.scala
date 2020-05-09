package net.scalytica.kafka.wsproxy.codecs

import java.util.{Map => JMap}

import com.sksamuel.avro4s._
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient
import io.confluent.kafka.serializers.{
  KafkaAvroDeserializer,
  KafkaAvroSerializer
}
import net.scalytica.kafka.wsproxy.StringExtensions
import net.scalytica.kafka.wsproxy.logging.WithProxyLogger
import org.apache.avro.generic.GenericRecord
import org.apache.kafka.common.serialization.{Deserializer, Serde, Serializer}

import scala.collection.JavaConverters._
import scala.reflect._
import scala.util.control.NonFatal

// scalastyle:off
class WsProxyAvroSerde[T <: Product: Decoder: Encoder: SchemaFor: ClassTag] private (
    avroSer: KafkaAvroSerializer,
    avroDes: KafkaAvroDeserializer
) extends Serde[T]
    with Serializer[T]
    with Deserializer[T]
    with Serializable
    with WithProxyLogger {
  // scalastyle:on

  override def configure(configs: JMap[String, _], isKey: Boolean): Unit = {
    avroSer.configure(configs, isKey)
    avroDes.configure(configs, isKey)
  }

  override def serializer(): Serializer[T]     = this
  override def deserializer(): Deserializer[T] = this

  // scalastyle:off null
  def serialize(data: T): Array[Byte]   = serialize(null, data)
  def deserialize(data: Array[Byte]): T = deserialize(null, data)
  // scalastyle:on null

  override def serialize(topic: String, data: T): Array[Byte] = {
    val topicStr = Option(topic).getOrElse("NA")
    logger.trace(
      s"Attempting to serialize ${data.getClass.getSimpleName}" +
        s"${topicStr.asOption.map(t => s" for $t").getOrElse("")}"
    )
    val res = Option(data).map { d =>
      val record = RecordFormat[T].to(d)
      avroSer.serialize(topic, record)
    }

    res.orNull
  }

  override def deserialize(topic: String, data: Array[Byte]): T = {
    val topicStr = Option(topic).getOrElse("NA")
    logger.trace(
      s"Attempting to deserialize message from topic $topicStr"
    )
    logger.trace(
      s"Data array has length ${Option(data).map(_.length).orNull}"
    )
    try {
      val record = avroDes.deserialize(topic, data).asInstanceOf[GenericRecord]
      logger.trace(s"GenericRecord is:\n$record")
      val res = RecordFormat[T].from(record)
      res
    } catch {
      case NonFatal(ex) =>
        logger.error(s"Could not deserialize data from $topicStr", ex)
        throw ex
    }
  }

  override def close(): Unit = {
    avroSer.close()
    avroDes.close()
  }

}

object WsProxyAvroSerde extends WithProxyLogger {

  private[this] def init[T <: Product: Decoder: Encoder: SchemaFor: ClassTag](
      client: Option[SchemaRegistryClient],
      configs: Map[String, _],
      isKey: Boolean
  ): WsProxyAvroSerde[T] = {
    val serde = client match {
      case Some(c) =>
        new WsProxyAvroSerde[T](
          new KafkaAvroSerializer(c),
          new KafkaAvroDeserializer(c)
        )

      case None =>
        new WsProxyAvroSerde[T](
          new KafkaAvroSerializer(),
          new KafkaAvroDeserializer()
        )
    }
    serde.configure(configs.asJava, isKey)

    logger.debug(
      s"Initialised ${serde.getClass} with for ${classTag[T].runtimeClass}" +
        s" configuration:${configs.mkString("\n", "\n", "")}"
    )

    serde
  }

  def apply[T <: Product: Decoder: Encoder: SchemaFor: ClassTag]()
      : WsProxyAvroSerde[T] =
    init[T](None, Map.empty[String, Any], isKey = false)

  def apply[T <: Product: Decoder: Encoder: SchemaFor: ClassTag](
      configs: Map[String, _]
  ): WsProxyAvroSerde[T] = init[T](None, configs, isKey = false)

  def apply[T <: Product: Decoder: Encoder: SchemaFor: ClassTag](
      configs: Map[String, _],
      isKey: Boolean
  ): WsProxyAvroSerde[T] = init[T](None, configs, isKey)

  def apply[T <: Product: Decoder: Encoder: SchemaFor: ClassTag](
      client: SchemaRegistryClient,
      configs: Map[String, _]
  ): WsProxyAvroSerde[T] = init[T](Option(client), configs, isKey = false)

  def apply[T <: Product: Decoder: Encoder: SchemaFor: ClassTag](
      client: SchemaRegistryClient,
      configs: Map[String, _],
      isKey: Boolean
  ): WsProxyAvroSerde[T] = init[T](Option(client), configs, isKey)

  def apply[T <: Product: Decoder: Encoder: SchemaFor: ClassTag](
      isKey: Boolean
  )(
      implicit
      client: Option[SchemaRegistryClient],
      configs: Map[String, _]
  ): WsProxyAvroSerde[T] = init[T](client, configs, isKey)

  def apply[T <: Product: Decoder: Encoder: SchemaFor: ClassTag](
      isKey: Boolean
  )(implicit configs: Map[String, _]): WsProxyAvroSerde[T] =
    init[T](None, configs, isKey)

}
