package net.scalytica.kafka.wsproxy

import com.typesafe.config.ConfigFactory
import net.scalytica.kafka.wsproxy.Configuration.AppCfg
import org.scalatest.{MustMatchers, WordSpec}
import pureconfig.error.ConfigReaderException

class ConfigurationSpec extends WordSpec with MustMatchers {

  val invalidCfg1 = ConfigFactory.parseString(
    """kafka.ws.proxy {
      |  server {
      |    port = 8078
      |    kafka-bootstrap-urls = ["localhost:29092"]
      |    schema-registry-url = "http://localhost:28081"
      |    auto-register-schemas = true
      |  }
      |
      |  consumer {
      |    default-rate-limit = unlimited
      |    default-batch-size = 0
      |  }
      |
      |  commit-handler {
      |    max-stack-size: 200
      |    auto-commit-enabled: false
      |    auto-commit-interval: 1 seconds
      |    auto-commit-max-age: 20 seconds
      |  }
      |}""".stripMargin
  )

  val invalidCfg2 = ConfigFactory.parseString(
    """kafka.ws.proxy {
      |  server {
      |    port = 8078
      |    kafka-bootstrap-urls = "localhost:29092"
      |    schema-registry-url = "http://localhost:28081"
      |    auto-register-schemas = true
      |  }
      |
      |  consumer {
      |    default-rate-limit = 0
      |    default-batch-size = 0
      |  }
      |
      |  commit-handler {
      |    max-stack-size: 200
      |    auto-commit-enabled: false
      |    auto-commit-interval: 1 seconds
      |    auto-commit-max-age: 20 seconds
      |  }
      |}""".stripMargin
  )

  "The Configuration" should {

    "successfully load the default configuration" in {
      val cfg = Configuration.load()

      cfg.consumer.defaultBatchSize mustBe 0
      cfg.consumer.defaultRateLimit mustBe 0
      cfg.server.kafkaBootstrapUrls mustBe Seq("localhost:29092")
      cfg.server.schemaRegistryUrl mustBe Some("http://localhost:28081")
      cfg.server.autoRegisterSchemas mustBe true
    }

    "fail when trying to load an invalid configuration" in {
      a[ConfigReaderException[AppCfg]] should be thrownBy Configuration
        .loadConfig(invalidCfg1)

      a[ConfigReaderException[AppCfg]] should be thrownBy Configuration
        .loadConfig(invalidCfg2)
    }

  }

}
