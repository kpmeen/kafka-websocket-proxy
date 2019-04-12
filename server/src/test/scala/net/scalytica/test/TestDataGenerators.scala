package net.scalytica.test

trait TestDataGenerators {

  def producerKeyValueJson(num: Int): Seq[String] = {
    (1 to num).map { i =>
      s"""{
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

  def producerValueJson(num: Int): Seq[String] = {
    (1 to num).map { i =>
      s"""{
         |  "value": {
         |    "value": "bar-$i",
         |    "format": "string"
         |  }
         |}""".stripMargin
    }
  }
}
