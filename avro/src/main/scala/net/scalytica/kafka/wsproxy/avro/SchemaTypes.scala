package net.scalytica.kafka.wsproxy.avro

import com.sksamuel.avro4s._
import org.apache.avro.Schema
import shapeless.{:+:, CNil, Coproduct}

object SchemaTypes {

  /**
   * Simple representation of Kafka message headers. Currently there is only
   * support for String values.
   *
   * @param key
   *   The header key
   * @param value
   *   The header value
   */
  @AvroDoc("Schema definition for simple Kafka message headers.")
  @AvroNamespace("net.scalytica.kafka.wsproxy.avro")
  case class KafkaMessageHeader(key: String, value: String)

  // format: off
  type AvroValueTypesCoproduct =
    Array[Byte] :+: String :+: Int :+: Long :+: Double :+: Float :+: CNil
  // format: on

  val EmptyValueType: AvroValueTypesCoproduct =
    Coproduct[AvroValueTypesCoproduct](Array.empty[Byte])

  /**
   * Wrapper schema for messages to produce into Kafka topics via the WebSocket
   * Proxy.
   *
   * @param key
   *   The message key. Defined as a union type using shapeless co-products with
   *   a subset of available types that can be used.
   * @param value
   *   The message value.
   * @param headers
   *   The headers to apply to the message in Kafka.
   */
  @AvroDoc(
    "Inbound schema for producing messages with a key and value to Kafka " +
      "topics via the WebSocket proxy. It is up to the client to serialize " +
      "the key and value before adding them to this message. This is because " +
      "Avro does not support referencing external/remote schemas."
  )
  @AvroNamespace("net.scalytica.kafka.wsproxy.avro")
  case class AvroProducerRecord(
      // format: off
      key: Option[AvroValueTypesCoproduct] = None,
      // format: on
      value: AvroValueTypesCoproduct,
      headers: Option[Seq[KafkaMessageHeader]] = None
  ) {

    def isEmpty: Boolean = AvroProducerRecord.Empty == this

  }

  object AvroProducerRecord {

    lazy val Empty: AvroProducerRecord =
      AvroProducerRecord(value = EmptyValueType)

    implicit val schemaFor = SchemaFor[AvroProducerRecord]
    implicit val encoder   = Encoder[AvroProducerRecord]
    implicit val decoder   = Decoder[AvroProducerRecord]

    val schema: Schema = AvroSchema[AvroProducerRecord]
  }

  /**
   * Schema for confirmation messages sent back through the producer socket when
   * a message has been successfully sent to Kafka.
   *
   * @param topic
   *   The topic the message was written to.
   * @param partition
   *   The topic partition the message was written to.
   * @param offset
   *   The topic partition offset for the given message.
   * @param timestamp
   *   The timestamp for when the message was written to Kafka.
   */
  @AvroDoc("Outbound schema for responding to produced messages.")
  @AvroNamespace("net.scalytica.kafka.wsproxy.avro")
  case class AvroProducerResult(
      topic: String,
      partition: Int,
      offset: Long,
      timestamp: Long
  )

  object AvroProducerResult {
    implicit val schemaFor = SchemaFor[AvroProducerResult]
    implicit val encoder   = Encoder[AvroProducerResult]
    implicit val decoder   = Decoder[AvroProducerResult]

    val schema: Schema = AvroSchema[AvroProducerResult]
  }

  /**
   * Outbound schema for consumed messages with Avro key and value. It is up to
   * the client to deserialize the key and value using the correct schemas,
   * since these are passed through as raw byte arrays in this wrapper message.
   *
   * @param wsProxyMessageId
   *   The message ID for this record.
   * @param topic
   *   The topic the message came from.
   * @param partition
   *   The topic partition the message came from.
   * @param offset
   *   The topic partition offset for the message.
   * @param timestamp
   *   The timestamp for when the message was written to Kafka.
   * @param key
   *   The message key.
   * @param value
   *   The message value.
   * @param headers
   *   The headers applied to the message in Kafka.
   */
  @AvroDoc(
    "Outbound schema for messages with Avro key and value. It is up to the " +
      "client to deserialize the key and value using the correct schemas, " +
      "since these are passed through as raw byte arrays in this wrapper " +
      "message."
  )
  @AvroNamespace("net.scalytica.kafka.wsproxy.avro")
  case class AvroConsumerRecord(
      wsProxyMessageId: String,
      topic: String,
      partition: Int,
      offset: Long,
      timestamp: Long,
      key: Option[AvroValueTypesCoproduct],
      value: AvroValueTypesCoproduct,
      headers: Option[Seq[KafkaMessageHeader]]
  )

  object AvroConsumerRecord {
    implicit val schemaFor = SchemaFor[AvroConsumerRecord]
    implicit val encoder   = Encoder[AvroConsumerRecord]
    implicit val decoder   = Decoder[AvroConsumerRecord]

    val schema: Schema = AvroSchema[AvroConsumerRecord]
  }

  /**
   * Schema to use when committing consumed Kafka messages through an outbound
   * WebSocket.
   *
   * @param wsProxyMessageId
   *   The message ID to commit.
   */
  @AvroDoc("Inbound schema for committing the offset of consumed messages.")
  @AvroNamespace("net.scalytica.kafka.wsproxy.avro")
  case class AvroCommit(wsProxyMessageId: String)

  object AvroCommit {
    implicit val schemaFor = SchemaFor[AvroCommit]
    implicit val encoder   = Encoder[AvroCommit]
    implicit val decoder   = Decoder[AvroCommit]

    val schema: Schema = AvroSchema[AvroCommit]
  }
}
