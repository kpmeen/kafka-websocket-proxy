package net.scalytica.kafka.wsproxy

import com.typesafe.config.ConfigFactory
import net.scalytica.kafka.wsproxy.Configuration.AppConfig
import org.scalatest.{MustMatchers, WordSpec}
import pureconfig.error.ConfigReaderException

class ConfigurationSpec extends WordSpec with MustMatchers {

  val invalidCfg1 = ConfigFactory.parseString(
    """kafka.websocket.proxy {
      |  kafka-bootstrap-urls = ["localhost:29092"]
      |  default-rate-limit = unlimited
      |  default-batch-size = 0
      |}""".stripMargin
  )

  val invalidCfg2 = ConfigFactory.parseString(
    """kafka.websocket.proxy {
      |  kafka-bootstrap-urls = "localhost:29092"
      |  default-rate-limit = 0
      |  default-batch-size = 0
      |}""".stripMargin
  )

  "The Configuration" should {

    "successfully load the default configuration" in {
      val cfg = Configuration.load()

      cfg.defaultBatchSize mustBe 0
      cfg.defaultRateLimit mustBe 0
      cfg.kafkaBootstrapUrls mustBe Seq("localhost:29092")
    }

    "fail when trying to load an invalid configuration" in {
      a[ConfigReaderException[AppConfig]] should be thrownBy Configuration
        .loadConfig(invalidCfg1)

      a[ConfigReaderException[AppConfig]] should be thrownBy Configuration
        .loadConfig(invalidCfg2)
    }

  }

}
