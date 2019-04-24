package net.scalytica.kafka.wsproxy.avro

import com.sksamuel.avro4s._

object SchemaTypes {

  object Implicits {

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
   * Wrapper schema for messages to produce into Kafka topics via the WebSocket
   * Proxy.
   *
   * @param key The message key.
   * @param value The message value.
   */
  @AvroDoc(
    """Inbound schema for producing messages with a key and value to Kafka
       topics via the WebSocket proxy. It is up to the client to serialize the
       key and value before adding them to this message. This is because Avro
       does not support referencing external/remote schemas."""
  )
  case class AvroProducerRecord(
      key: Option[Array[Byte]],
      value: Array[Byte]
  ) extends WsProxyAvroRecord

  object AvroProducerRecord {
    val schemaFor  = SchemaFor[AvroProducerRecord]
    val toRecord   = ToRecord[AvroProducerRecord]
    val fromRecord = FromRecord[AvroProducerRecord]

    lazy val schema = AvroSchema[AvroProducerRecord]
  }

  /**
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
   *
   * @param wsProxyMessageId The message ID for this record.
   * @param topic The topic the message came from.
   * @param partition The topic partition the message came from.
   * @param offset The topic partition offset for the message.
   * @param timestamp The timestamp for when the message was written to Kafka.
   * @param key The message key.
   * @param value The message value.
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
      value: Array[Byte]
  ) extends WsProxyAvroRecord

  object AvroConsumerRecord {
    val schemaFor  = SchemaFor[AvroConsumerRecord]
    val toRecord   = ToRecord[AvroConsumerRecord]
    val fromRecord = FromRecord[AvroConsumerRecord]

    lazy val schema = AvroSchema[AvroConsumerRecord]
  }

  /**
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
