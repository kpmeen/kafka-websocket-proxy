package net.scalytica.kafka.wsproxy.codecs

import java.util.{Map => JMap}

import com.sksamuel.avro4s._
import com.sksamuel.avro4s.kafka.GenericSerde
import net.scalytica.kafka.wsproxy.StringExtensions
import net.scalytica.kafka.wsproxy.logging.WithProxyLogger
import org.apache.kafka.common.serialization.{Deserializer, Serde, Serializer}

import scala.collection.JavaConverters._
import scala.reflect._
import scala.util.control.NonFatal

// scalastyle:off
class WsProxyAvroSerde[
    T >: Null: SchemaFor: Encoder: Decoder
] extends Serde[T]
    with Serializer[T]
    with Deserializer[T]
    with Serializable
    with WithProxyLogger {
  // scalastyle:on

  val innerSerde = new GenericSerde[T](BinaryFormat)

  override def configure(configs: JMap[String, _], isKey: Boolean): Unit = {
    // DO NOTHING
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
    val res = Option(data).map(d => innerSerde.serialize(topic, d))

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
      val record = innerSerde.deserialize(topic, data)
      logger.trace(s"Record is:\n$record")
      record
    } catch {
      case NonFatal(ex) =>
        logger.error(s"Could not deserialize data from $topicStr", ex)
        throw ex
    }
  }

  override def close(): Unit = {
    innerSerde.close()
  }

}

object WsProxyAvroSerde extends WithProxyLogger {

  private[this] def init[T >: Null: SchemaFor: Encoder: Decoder: ClassTag](
      configs: Map[String, _],
      isKey: Boolean
  ): WsProxyAvroSerde[T] = {
    val serde = new WsProxyAvroSerde[T]
    serde.configure(configs.asJava, isKey)

    logger.debug(
      s"Initialised ${serde.getClass} with for ${classTag[T].runtimeClass}" +
        s" configuration:${configs.mkString("\n", "\n", "")}"
    )

    serde
  }

  def keySerde[T >: Null: SchemaFor: Encoder: Decoder: ClassTag]
      : WsProxyAvroSerde[T] =
    init[T](Map.empty, isKey = true)

  def valueSerde[T >: Null: SchemaFor: Encoder: Decoder: ClassTag]
      : WsProxyAvroSerde[T] =
    init[T](Map.empty, isKey = false)

  def apply[T >: Null: SchemaFor: Encoder: Decoder: ClassTag]()
      : WsProxyAvroSerde[T] =
    init[T](Map.empty[String, Any], isKey = false)

  def apply[T >: Null: SchemaFor: Encoder: Decoder: ClassTag](
      configs: Map[String, _]
  ): WsProxyAvroSerde[T] = init[T](configs, isKey = false)

  def apply[T >: Null: SchemaFor: Encoder: Decoder: ClassTag](
      configs: Map[String, _],
      isKey: Boolean
  ): WsProxyAvroSerde[T] = init[T](configs, isKey)

  def apply[T >: Null: SchemaFor: Encoder: Decoder: ClassTag](
      isKey: Boolean
  )(implicit configs: Map[String, _]): WsProxyAvroSerde[T] =
    init[T](configs, isKey)

}
