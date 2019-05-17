package net.scalytica.kafka.wsproxy

import com.typesafe.config.ConfigFactory
import net.scalytica.kafka.wsproxy.Configuration.{AppCfg, KafkaBootstrapUrls}
import org.scalatest.{MustMatchers, WordSpec}
import pureconfig.error.ConfigReaderException

import scala.concurrent.duration._

class ConfigurationSpec extends WordSpec with MustMatchers {

  val invalidCfg1 = ConfigFactory.parseString(
    """kafka.ws.proxy {
      |  server {
      |    server-id = "node-1"
      |    port = 8078
      |    kafka-bootstrap-urls = "localhost:29092"
      |    schema-registry-url = "http://localhost:28081"
      |    auto-register-schemas = true
      |  }
      |
      |  admin-client {
      |    kafka-client-properties {}
      |  }
      |
      |  consumer {
      |    default-rate-limit = unlimited
      |    default-batch-size = 0
      |    kafka-client-properties {}
      |  }
      |
      |  producer {
      |    kafka-client-properties {}
      |  }
      |
      |  session-handler {
      |    session-state-topic-name = "_wsproxy.session.state"
      |    session-state-replication-factor = 3
      |    session-state-retention = 30 days
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
      |    server-id = "node-1"
      |    port = 8078
      |    kafka-bootstrap-urls = "localhost:29092"
      |    schema-registry-url = "http://localhost:28081"
      |    auto-register-schemas = true
      |  }
      |
      |  admin-client {
      |    kafka-client-properties {}
      |  }
      |
      |  consumer {
      |    default-rate-limit = 0
      |    default-batch-size = 0
      |    kafka-client-properties {}
      |  }
      |
      |  producer {
      |    kafka-client-properties {}
      |  }
      |
      |  session-handler {
      |    session-state-topic-name = "_wsproxy.session.state"
      |    session-state-replication-factor = 3
      |    session-state-retention = 30 days
      |  }
      |
      |  commit-handler {
      |    max-stack: 200
      |    auto-commit-enabled: false
      |    auto-commit-interval: 1 seconds
      |    auto-commit-max-age: 20 seconds
      |  }
      |}""".stripMargin
  )

  "The Configuration" should {

    "successfully load the default configuration" in {
      val cfg = Configuration.load()

      cfg.server.serverId.value mustBe "node-1"
      cfg.server.port mustBe 8078
      cfg.server.kafkaBootstrapUrls mustBe KafkaBootstrapUrls(
        List("localhost:29092")
      )
      cfg.server.schemaRegistryUrl mustBe Some("http://localhost:28081")
      cfg.server.autoRegisterSchemas mustBe true

      cfg.consumer.defaultBatchSize mustBe 0
      cfg.consumer.defaultRateLimit mustBe 0

      cfg.sessionHandler.sessionStateTopicName.value mustBe "_wsproxy.session.state" // scalastyle:ignore
      cfg.sessionHandler.sessionStateReplicationFactor mustBe 3
      cfg.sessionHandler.sessionStateRetention mustBe 30.days

      cfg.commitHandler.maxStackSize mustBe 100
      cfg.commitHandler.autoCommitEnabled mustBe false
      cfg.commitHandler.autoCommitInterval mustBe 1.second
      cfg.commitHandler.autoCommitMaxAge mustBe 20.seconds
    }

    "fail when trying to load an invalid configuration" in {
      a[ConfigReaderException[AppCfg]] should be thrownBy Configuration
        .loadConfig(invalidCfg1)

      a[ConfigReaderException[AppCfg]] should be thrownBy Configuration
        .loadConfig(invalidCfg2)
    }

  }

}
