package net.scalytica.kafka.wsproxy.codecs

import java.util.{Map => JMap}

import com.sksamuel.avro4s._
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient
import io.confluent.kafka.serializers.{
  KafkaAvroDeserializer,
  KafkaAvroSerializer
}
import net.scalytica.kafka.wsproxy.logging.WithProxyLogger
import org.apache.avro.generic.GenericRecord
import org.apache.kafka.common.serialization.{Deserializer, Serde, Serializer}

import scala.collection.JavaConverters._
import scala.util.control.NonFatal

// scalastyle:off
class WsProxyAvroSerde[T <: Product: Decoder: Encoder: SchemaFor] private (
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

  override def serialize(topic: String, data: T): Array[Byte] = {
    val topicStr = Option(topic).getOrElse("NA")
    logger.trace(
      s"Attempting to serialize ${data.getClass.getSimpleName} to for $topicStr"
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

object WsProxyAvroSerde {

  def apply[T <: Product: Decoder: Encoder: SchemaFor]()
      : WsProxyAvroSerde[T] = {
    val cas = new WsProxyAvroSerde[T](
      new KafkaAvroSerializer(),
      new KafkaAvroDeserializer()
    )
    cas.configure(Map.empty[String, Any].asJava, isKey = false)
    cas
  }

  def apply[T <: Product: Decoder: Encoder: SchemaFor](
      configs: Map[String, _]
  ): WsProxyAvroSerde[T] = {
    val cas = new WsProxyAvroSerde[T](
      new KafkaAvroSerializer(),
      new KafkaAvroDeserializer()
    )
    cas.configure(configs.asJava, isKey = false)
    cas
  }

  def apply[T <: Product: Decoder: Encoder: SchemaFor](
      configs: Map[String, _],
      isKey: Boolean
  ): WsProxyAvroSerde[T] = {
    val cas = new WsProxyAvroSerde[T](
      new KafkaAvroSerializer(),
      new KafkaAvroDeserializer()
    )
    cas.configure(configs.asJava, isKey)
    cas
  }

  def apply[T <: Product: Decoder: Encoder: SchemaFor](
      client: SchemaRegistryClient,
      configs: Map[String, _]
  ): WsProxyAvroSerde[T] = {
    val cas = new WsProxyAvroSerde[T](
      new KafkaAvroSerializer(client),
      new KafkaAvroDeserializer(client)
    )
    cas.configure(configs.asJava, isKey = false)
    cas
  }

  def apply[T <: Product: Decoder: Encoder: SchemaFor](
      client: SchemaRegistryClient,
      configs: Map[String, _],
      isKey: Boolean
  ): WsProxyAvroSerde[T] = {
    val cas = new WsProxyAvroSerde[T](
      new KafkaAvroSerializer(client),
      new KafkaAvroDeserializer(client)
    )
    cas.configure(configs.asJava, isKey)
    cas
  }

  def apply[T <: Product: Decoder: Encoder: SchemaFor](
      isKey: Boolean
  )(
      implicit
      client: Option[SchemaRegistryClient],
      configs: Map[String, _]
  ): WsProxyAvroSerde[T] =
    client
      .map { rc =>
        WsProxyAvroSerde[T](
          client = rc,
          configs = configs,
          isKey = isKey
        )
      }
      .getOrElse {
        WsProxyAvroSerde[T](
          configs = configs,
          isKey = isKey
        )
      }

  def apply[T <: Product: Decoder: Encoder: SchemaFor](
      isKey: Boolean
  )(implicit configs: Map[String, _]): WsProxyAvroSerde[T] =
    WsProxyAvroSerde[T](
      configs = configs,
      isKey = isKey
    )

}
