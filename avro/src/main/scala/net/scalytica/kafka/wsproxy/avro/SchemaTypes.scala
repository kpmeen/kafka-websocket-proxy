package net.scalytica.kafka.wsproxy.avro

import com.sksamuel.avro4s._

object SchemaTypes {

  object Implicits {

    implicit val KafkaMessageHeaderSchemaFor: SchemaFor[KafkaMessageHeader] =
      KafkaMessageHeader.schemaFor
    implicit val KafkaMessageHeaderToRecord: ToRecord[KafkaMessageHeader] =
      KafkaMessageHeader.toRecord
    implicit val KafkaMessageHeaderFromRecord: FromRecord[KafkaMessageHeader] =
      KafkaMessageHeader.fromRecord

    implicit val ProducerRecordSchemaFor: SchemaFor[AvroProducerRecord] =
      AvroProducerRecord.schemaFor
    implicit val ProducerRecordToRecord: ToRecord[AvroProducerRecord] =
      AvroProducerRecord.toRecord
    implicit val ProducerRecordFromRecord: FromRecord[AvroProducerRecord] =
      AvroProducerRecord.fromRecord

    implicit val ProducerResultSchemaFor: SchemaFor[AvroProducerResult] =
      AvroProducerResult.schemaFor
    implicit val ProducerResultToRecord: ToRecord[AvroProducerResult] =
      AvroProducerResult.toRecord
    implicit val ProducerResultFromRecord: FromRecord[AvroProducerResult] =
      AvroProducerResult.fromRecord

    implicit val ConsumerRecordSchemaFor: SchemaFor[AvroConsumerRecord] =
      AvroConsumerRecord.schemaFor
    implicit val ConsumerRecordToRecord: ToRecord[AvroConsumerRecord] =
      AvroConsumerRecord.toRecord
    implicit val ConsumerRecordFromRecord: FromRecord[AvroConsumerRecord] =
      AvroConsumerRecord.fromRecord

    implicit val CommitSchemaFor: SchemaFor[AvroCommit] = AvroCommit.schemaFor
    implicit val CommitToRecord: ToRecord[AvroCommit]   = AvroCommit.toRecord
    implicit val CommitFromRecord: FromRecord[AvroCommit] =
      AvroCommit.fromRecord

  }

  trait WsProxyAvroRecord

  /**
   * Simple representation of Kafka message headers. Currently there is only
   * support for String values.
   *
   * @param key The header key
   * @param value The header value
   */
  @AvroDoc("Schema definition for simple Kafka message headers.")
  case class KafkaMessageHeader(key: String, value: String)
      extends WsProxyAvroRecord

  object KafkaMessageHeader {
    val schemaFor  = SchemaFor[KafkaMessageHeader]
    val toRecord   = ToRecord[KafkaMessageHeader]
    val fromRecord = FromRecord[KafkaMessageHeader]

    lazy val schema = AvroSchema[KafkaMessageHeader]
  }

  /**
   * Wrapper schema for messages to produce into Kafka topics via the WebSocket
   * Proxy.
   *
   * @param key The message key.
   * @param value The message value.
   * @param headers The headers to apply to the message in Kafka.
   */
  @AvroDoc(
    """Inbound schema for producing messages with a key and value to Kafka
       topics via the WebSocket proxy. It is up to the client to serialize the
       key and value before adding them to this message. This is because Avro
       does not support referencing external/remote schemas."""
  )
  case class AvroProducerRecord(
      key: Option[Array[Byte]],
      value: Array[Byte],
      headers: Option[Seq[KafkaMessageHeader]]
  ) extends WsProxyAvroRecord {

    def isEmpty: Boolean = AvroProducerRecord.empty == this

  }

  object AvroProducerRecord {
    lazy val empty: AvroProducerRecord =
      AvroProducerRecord(None, Array.empty, None)

    val schemaFor  = SchemaFor[AvroProducerRecord]
    val toRecord   = ToRecord[AvroProducerRecord]
    val fromRecord = FromRecord[AvroProducerRecord]

    lazy val schema = AvroSchema[AvroProducerRecord]
  }

  /**
   * Schema for confirmation messages sent back through the producer socket when
   * a message has been successfully sent to Kafka.
   *
   * @param topic The topic the message was written to.
   * @param partition The topic partition the message was written to.
   * @param offset The topic partition offset for the given message.
   * @param timestamp The timestamp for when the message was written to Kafka.
   */
  @AvroDoc("Outbound schema for responding to produced messages.")
  case class AvroProducerResult(
      topic: String,
      partition: Int,
      offset: Long,
      timestamp: Long
  ) extends WsProxyAvroRecord

  object AvroProducerResult {
    val schemaFor  = SchemaFor[AvroProducerResult]
    val toRecord   = ToRecord[AvroProducerResult]
    val fromRecord = FromRecord[AvroProducerResult]

    lazy val schema = AvroSchema[AvroProducerResult]
  }

  /**
   * Outbound schema for consumed messages with Avro key and value. It is up to
   * the client to deserialize the key and value using the correct schemas,
   * since these are passed through as raw byte arrays in this wrapper message.
   *
   * @param wsProxyMessageId The message ID for this record.
   * @param topic The topic the message came from.
   * @param partition The topic partition the message came from.
   * @param offset The topic partition offset for the message.
   * @param timestamp The timestamp for when the message was written to Kafka.
   * @param key The message key.
   * @param value The message value.
   * @param headers The headers applied to the message in Kafka.
   */
  @AvroDoc(
    """Outbound schema for messages with Avro key and value. It is up to the
       client to deserialize the key and value using the correct schemas, since
       these are passed through as raw byte arrays in this wrapper message."""
  )
  case class AvroConsumerRecord(
      wsProxyMessageId: String,
      topic: String,
      partition: Int,
      offset: Long,
      timestamp: Long,
      key: Option[Array[Byte]],
      value: Array[Byte],
      headers: Option[Seq[KafkaMessageHeader]]
  ) extends WsProxyAvroRecord

  object AvroConsumerRecord {
    val schemaFor  = SchemaFor[AvroConsumerRecord]
    val toRecord   = ToRecord[AvroConsumerRecord]
    val fromRecord = FromRecord[AvroConsumerRecord]

    lazy val schema = AvroSchema[AvroConsumerRecord]
  }

  /**
   * Schema to use when committing consumed Kafka messages through an outbound
   * websocket.
   *
   * @param wsProxyMessageId The message ID to commit.
   */
  @AvroDoc("Inbound schema for committing the offset of consumed messages.")
  case class AvroCommit(wsProxyMessageId: String) extends WsProxyAvroRecord

  object AvroCommit {
    val schemaFor  = SchemaFor[AvroCommit]
    val toRecord   = ToRecord[AvroCommit]
    val fromRecord = FromRecord[AvroCommit]

    lazy val schema = AvroSchema[AvroCommit]
  }
}
