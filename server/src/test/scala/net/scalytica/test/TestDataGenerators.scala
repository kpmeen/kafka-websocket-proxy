package net.scalytica.test

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

}

object TestDataGenerators extends TestDataGenerators
