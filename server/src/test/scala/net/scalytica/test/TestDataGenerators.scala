package net.scalytica.test

import net.scalytica.kafka.wsproxy.config.Configuration.{
  ConsumerSpecificLimitCfg,
  ProducerSpecificLimitCfg
}
import net.scalytica.kafka.wsproxy.models.{
  Offset,
  Partition,
  PartitionOffsetMetadata,
  TopicName,
  WsGroupId,
  WsProducerId
}

// scalastyle:off magic.number
trait TestDataGenerators {

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

  def createJsonKeyValue(
      num: Int,
      withHeaders: Boolean = false,
      withMessageId: Boolean = false
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

      val messageId =
        if (withMessageId)
          s"""
             |  "messageId": "messageId$i",""".stripMargin
        else ""

      s"""{$headers$messageId
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

  def createJsonValue(
      num: Int,
      withHeaders: Boolean = false,
      withMessageId: Boolean = false
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

      val messageId =
        if (withMessageId)
          s"""
             |  "messageId": "messageId$i",""".stripMargin
        else ""

      s"""{$headers$messageId
         |  "value": {
         |    "value": "bar-$i",
         |    "format": "string"
         |  }
         |}""".stripMargin
    }
  }

  def createProducerCfg(
      id: String,
      mps: Option[Int],
      mc: Option[Int]
  ): ProducerSpecificLimitCfg = {
    ProducerSpecificLimitCfg(
      producerId = WsProducerId(id),
      messagesPerSecond = mps,
      maxConnections = mc
    )
  }

  def createConsumerCfg(
      id: String,
      mps: Option[Int],
      mc: Option[Int],
      bs: Option[Int] = None
  ): ConsumerSpecificLimitCfg = {
    ConsumerSpecificLimitCfg(
      groupId = WsGroupId(id),
      messagesPerSecond = mps,
      maxConnections = mc,
      batchSize = bs
    )
  }

  def createPartitionOffsetMetadataList(
      topicName: TopicName,
      numPartitions: Int = 3,
      offset: Long = 10L
  ): List[PartitionOffsetMetadata] = {
    (0 until numPartitions).map { i =>
      PartitionOffsetMetadata(
        topic = topicName,
        partition = Partition(i),
        offset = Offset(offset),
        metadata = None
      )
    }.toList
  }

}

object TestDataGenerators extends TestDataGenerators
