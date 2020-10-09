package net.scalytica.kafka.wsproxy.codecs

import java.util.{Map => JMap}

import com.sksamuel.avro4s._
import com.sksamuel.avro4s.kafka.GenericSerde
import net.scalytica.kafka.wsproxy.StringExtensions
import net.scalytica.kafka.wsproxy.logging.WithProxyLogger
import org.apache.kafka.common.serialization.{Deserializer, Serde, Serializer}

import scala.jdk.CollectionConverters._
import scala.reflect._
import scala.util.control.NonFatal

// scalastyle:off
class WsProxyAvroSerde[T >: Null: SchemaFor: Encoder: Decoder: ClassTag]
    extends Serde[T]
    with Serializer[T]
    with Deserializer[T]
    with Serializable
    with WithProxyLogger {
  // scalastyle:on

  private[this] val inner = new GenericSerde[T](BinaryFormat)

  private val schema = SchemaFor[T].schema

  private val cls = classTag[T].runtimeClass.getName

  logger.info(s"Init SerDes for $cls with schema:\n${schema.toString(true)}")

  override def configure(configs: JMap[String, _], isKey: Boolean): Unit = {
    // DO NOTHING
//    inner.configure(configs, isKey)
  }

  override def serializer(): Serializer[T]     = this
  override def deserializer(): Deserializer[T] = this

  // scalastyle:off null
  def serialize(data: T): Array[Byte]   = serialize(null, data)
  def deserialize(data: Array[Byte]): T = deserialize(null, data)
  // scalastyle:on null

  override def serialize(topic: String, data: T): Array[Byte] = {
    val tstr = topic.asOption.map(t => s"for topic $t").getOrElse("")

    logger.trace(s"Serializing $cls $tstr")
    logger.trace(s"Data to serialize: $data")

    Option(data).map(d => inner.serialize(topic, d)).orNull
  }

  override def deserialize(topic: String, data: Array[Byte]): T = {
    val tstr = topic.asOption.map(t => s" from topic $t").getOrElse("")

    logger.trace(s"Deserializing $cls $tstr")
    logger.trace(s"Data to deserialize: $data")

    Option(data).flatMap {
      case d if d.nonEmpty =>
        logger.debug(s"Data array has length ${d.length}")
        try {
          val record = inner.deserialize(topic, data)
          logger.trace(s"Record is: $record")
          Some(record)
        } catch {
          case NonFatal(ex) =>
            logger.error(s"Could not deserialize $cls from $tstr", ex)
            throw ex
        }
      case _ =>
        logger.debug("Data array is empty")
        None
    }.orNull
  }

  override def close(): Unit = {
    inner.close()
  }

}

object WsProxyAvroSerde extends WithProxyLogger {

  private[this] def init[T >: Null: SchemaFor: Encoder: Decoder: ClassTag](
      configs: Map[String, _],
      isKey: Boolean
  ): WsProxyAvroSerde[T] = {
    val serde = new WsProxyAvroSerde[T]
    serde.configure(configs.asJava, isKey)

    logger.trace(
      s"Initializing ${serde.getClass} for ${classTag[T].runtimeClass} with" +
        s" config: ${configs.mkString("\n", "\n", "")}"
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
