package net.scalytica.test

import net.scalytica.kafka.wsproxy.avro.SchemaTypes._
import shapeless.Coproduct

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

  def produceKeyValueJson(
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

  def produceValueJson(num: Int, withHeaders: Boolean = false): Seq[String] = {
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

  def produceKeyValueAvro(
      num: Int,
      withHeaders: Boolean = false
  ): Seq[AvroProducerRecord] = {
    val now = java.time.Instant.now().toEpochMilli

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

      val sk = TestSerdes.keySerdes.serialize(key)
      val k  = Coproduct[AvroValueTypesCoproduct](sk)
      val v  = TestSerdes.valueSerdes.serialize(value)

      AvroProducerRecord(key = Option(k), value = v, headers = headers)
    }
  }

  def produceKeyStringValueAvro(
      num: Int,
      withHeaders: Boolean = false
  ): Seq[AvroProducerRecord] = {
    (1 to num).map { i =>
      val headers =
        if (withHeaders) Option(Seq(KafkaMessageHeader(s"key$i", s"value$i")))
        else None
      val key = s"foo-$i"
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
        key = Option(Coproduct[AvroValueTypesCoproduct](key)),
        value = TestSerdes.valueSerdes.serialize(value),
        headers = headers
      )
    }
  }

  def produceValueAvro(
      num: Int,
      withHeaders: Boolean = false
  ): Seq[AvroProducerRecord] = {
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
        value = TestSerdes.valueSerdes.serialize("", value),
        headers = headers
      )
    }
  }
}
