package net.scalytica.kafka.wsproxy.avro

import net.scalytica.kafka.wsproxy.avro.SchemaTypes._
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec

class SchemaTypesSpec extends AnyWordSpec with Matchers {

  // scalastyle:off
  val expectedProducerRecordSchema =
    """{
      |  "type" : "record",
      |  "name" : "AvroProducerRecord",
      |  "namespace" : "net.scalytica.kafka.wsproxy.avro",
      |  "doc" : "Inbound schema for producing messages with a key and value to Kafka topics via the WebSocket proxy. It is up to the client to serialize the key and value before adding them to this message. This is because Avro does not support referencing external/remote schemas.",
      |  "fields" : [ {
      |    "name" : "key",
      |    "type" : [ "null", "bytes", "string", "int", "long", "double", "float" ],
      |    "default" : null
      |  }, {
      |    "name" : "value",
      |    "type" : [ "bytes", "string", "int", "long", "double", "float" ]
      |  }, {
      |    "name" : "headers",
      |    "type" : [ "null", {
      |      "type" : "array",
      |      "items" : {
      |        "type" : "record",
      |        "name" : "KafkaMessageHeader",
      |        "doc" : "Schema definition for simple Kafka message headers.",
      |        "fields" : [ {
      |          "name" : "key",
      |          "type" : "string"
      |        }, {
      |          "name" : "value",
      |          "type" : "string"
      |        } ]
      |      }
      |    } ],
      |    "default" : null
      |  }, {
      |    "name" : "clientMessageId",
      |    "type" : [ "null", "string" ],
      |    "default" : null
      |  } ]
      |}""".stripMargin

  val expectedProducerResultSchema =
    """{
      |  "type" : "record",
      |  "name" : "AvroProducerResult",
      |  "namespace" : "net.scalytica.kafka.wsproxy.avro",
      |  "doc" : "Outbound schema for responding to produced messages.",
      |  "fields" : [ {
      |    "name" : "topic",
      |    "type" : "string"
      |  }, {
      |    "name" : "partition",
      |    "type" : "int"
      |  }, {
      |    "name" : "offset",
      |    "type" : "long"
      |  }, {
      |    "name" : "timestamp",
      |    "type" : "long"
      |  }, {
      |    "name" : "clientMessageId",
      |    "type" : [ "null", "string" ],
      |    "default" : null
      |  } ]
      |}""".stripMargin

  val expectedConsumerRecordSchema =
    """{
      |  "type" : "record",
      |  "name" : "AvroConsumerRecord",
      |  "namespace" : "net.scalytica.kafka.wsproxy.avro",
      |  "doc" : "Outbound schema for messages with Avro key and value. It is up to the client to deserialize the key and value using the correct schemas, since these are passed through as raw byte arrays in this wrapper message.",
      |  "fields" : [ {
      |    "name" : "wsProxyMessageId",
      |    "type" : "string"
      |  }, {
      |    "name" : "topic",
      |    "type" : "string"
      |  }, {
      |    "name" : "partition",
      |    "type" : "int"
      |  }, {
      |    "name" : "offset",
      |    "type" : "long"
      |  }, {
      |    "name" : "timestamp",
      |    "type" : "long"
      |  }, {
      |    "name" : "key",
      |    "type" : [ "null", "bytes", "string", "int", "long", "double", "float" ]
      |  }, {
      |    "name" : "value",
      |    "type" : [ "bytes", "string", "int", "long", "double", "float" ]
      |  }, {
      |    "name" : "headers",
      |    "type" : [ "null", {
      |      "type" : "array",
      |      "items" : {
      |        "type" : "record",
      |        "name" : "KafkaMessageHeader",
      |        "doc" : "Schema definition for simple Kafka message headers.",
      |        "fields" : [ {
      |          "name" : "key",
      |          "type" : "string"
      |        }, {
      |          "name" : "value",
      |          "type" : "string"
      |        } ]
      |      }
      |    } ]
      |  } ]
      |}""".stripMargin

  val expectedCommitSchema =
    """{
      |  "type" : "record",
      |  "name" : "AvroCommit",
      |  "namespace" : "net.scalytica.kafka.wsproxy.avro",
      |  "doc" : "Inbound schema for committing the offset of consumed messages.",
      |  "fields" : [ {
      |    "name" : "wsProxyMessageId",
      |    "type" : "string"
      |  } ]
      |}""".stripMargin
  // scalastyle:on

  "SchemaTypes" should {

    "produce a schema for AvroProducerRecord" in {
      val actual = AvroProducerRecord.schema.toString(true)
      actual mustBe expectedProducerRecordSchema
    }

    "produce a schema for AvroProducerResult" in {
      val actual = AvroProducerResult.schema.toString(true)
      actual mustBe expectedProducerResultSchema
    }

    "produce a schema for AvroConsumerRecord" in {
      val actual = AvroConsumerRecord.schema.toString(true)
      actual mustBe expectedConsumerRecordSchema
    }

    "produce a schema for AvroCommit" in {
      val actual = AvroCommit.schema.toString(true)
      actual mustBe expectedCommitSchema
    }

  }

}
