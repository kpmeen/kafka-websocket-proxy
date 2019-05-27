package net.scalytica.test

import net.manub.embeddedkafka.schemaregistry.EmbeddedKafkaConfig
import net.scalytica.kafka.wsproxy.avro.SchemaTypes._

import scala.concurrent.duration._

trait TestDataGenerators extends TestTypes { self =>

  def sessionJson(
      groupId: String,
      consumer: Map[String, Int] = Map.empty
  ): String = {
    val consumersJson = consumer
      .map(c => s"""{ "id": "${c._1}", "serverId": ${c._2} }""")
      .mkString(",")

    s"""{
      |  "consumerGroupId": "$groupId",
      |  "consumers": [$consumersJson],
      |  "consumerLimit": 2
      |}""".stripMargin
  }

  def producerKeyValueJson(
      num: Int,
      withHeaders: Boolean = false
  ): Seq[String] = {
    (1 to num).map { i =>
      val headers =
        if (withHeaders)
          s"""
             |  "headers": [
             |    {
             |      "key": "key$i",
             |      "value": "value$i"
             |    }
             |  ],""".stripMargin
        else ""
      s"""{$headers
         |  "key": {
         |    "value": "foo-$i",
         |    "format": "string"
         |  },
         |  "value": {
         |    "value": "bar-$i",
         |    "format": "string"
         |  }
         |}""".stripMargin
    }
  }

  def producerValueJson(num: Int, withHeaders: Boolean = false): Seq[String] = {
    (1 to num).map { i =>
      val headers =
        if (withHeaders)
          s"""
             |  "headers": [
             |    {
             |      "key": "key$i",
             |      "value": "value$i"
             |    }
             |  ],""".stripMargin
        else ""
      s"""{$headers
         |  "value": {
         |    "value": "bar-$i",
         |    "format": "string"
         |  }
         |}""".stripMargin
    }
  }

  def producerKeyValueAvro(
      num: Int,
      withHeaders: Boolean = false
  )(implicit cfg: EmbeddedKafkaConfig): Seq[AvroProducerRecord] = {
    val now = java.time.Instant.now().toEpochMilli

    val keySerdes = Serdes.keySerdes(cfg.schemaRegistryPort)
    val valSerdes = Serdes.valueSerdes(cfg.schemaRegistryPort)

    (1 to num).map { i =>
      val headers =
        if (withHeaders) Option(Seq(KafkaMessageHeader(s"key$i", s"value$i")))
        else None
      val key = TestKey(s"foo-$i", now)
      val value = Album(
        artist = s"artist-$i",
        title = s"title-$i",
        tracks = (1 to 3).map { tnum =>
          Track(
            name = s"track-$tnum",
            duration = (120 seconds).toMillis
          )
        }
      )
      AvroProducerRecord(
        key = Option(keySerdes.serialize("", key)),
        value = valSerdes.serialize("", value),
        headers = headers
      )
    }
  }

  def producerValueAvro(
      num: Int,
      withHeaders: Boolean = false
  )(implicit cfg: EmbeddedKafkaConfig): Seq[AvroProducerRecord] = {
    val valSerdes = Serdes.valueSerdes(cfg.schemaRegistryPort)

    (1 to num).map { i =>
      val headers =
        if (withHeaders) Option(Seq(KafkaMessageHeader(s"key$i", s"value$i")))
        else None
      val value = Album(
        artist = s"artist-$i",
        title = s"title-$i",
        tracks = (1 to 3).map { tnum =>
          Track(
            name = s"track-$tnum",
            duration = (120 seconds).toMillis
          )
        }
      )
      AvroProducerRecord(
        key = None,
        value = valSerdes.serialize("", value),
        headers = headers
      )
    }
  }
}
