package net.scalytica.kafka.wsproxy

import com.typesafe.config.ConfigFactory
import net.scalytica.kafka.wsproxy.Configuration.{AppCfg, KafkaBootstrapHosts}
import org.scalatest.{MustMatchers, OptionValues, WordSpec}
import pureconfig.error.ConfigReaderException

import scala.concurrent.duration._

class ConfigurationSpec extends WordSpec with MustMatchers with OptionValues {

  val invalidCfg1 = ConfigFactory
    .parseString(
      s"""kafka.ws.proxy {
      |  server {
      |    server-id = "node-1"
      |    bind-interface = "localhost"
      |    // port = 8078 // missing key
      |  }
      |
      |  kafka-client {
      |    broker-resolution-timeout = 10 seconds
      |    bootstrap-hosts = "localhost:29092"
      |    schema-registry-url = "http://localhost:28081"
      |    auto-register-schemas = true
      |    monitoring-enabled = false
      |
      |    properties {
      |      security.protocol = PLAINTEXT
      |    }
      |
      |    confluent-monitoring {
      |      bootstrap-hosts = "localhost:29092"
      |      properties {
      |        security.protocol = PLAINTEXT
      |      }
      |    }
      |  }
      |
      |  admin-client {
      |    kafka-client-properties = $${kafka.ws.proxy.kafka-client.properties}
      |  }
      |
      |  consumer {
      |    default-rate-limit = 0
      |    default-batch-size = 0
      |    kafka-client-properties = $${kafka.ws.proxy.kafka-client.properties}
      |  }
      |
      |  producer {
      |    kafka-client-properties = $${kafka.ws.proxy.kafka-client.properties}
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
      |    auto-commit-interval: 1 second
      |    auto-commit-max-age: 20 seconds
      |  }
      |}""".stripMargin
    )
    .resolve()

  val invalidCfg2 = ConfigFactory
    .parseString(
      s"""kafka.ws.proxy {
      |  server {
      |    serverId = "node-1" // wrong key
      |    bind-interface = "localhost"
      |    port = 8078
      |  }
      |
      |  kafka-client {
      |    broker-resolution-timeout = 10 seconds
      |    kafka-bootstrap-hosts = "localhost:29092"
      |    schema-registry-url = "http://localhost:28081"
      |    auto-register-schemas = true
      |    monitoring-enabled = false
      |
      |    properties {
      |      security.protocol = PLAINTEXT
      |    }
      |
      |    confluent-monitoring {
      |      bootstrap-hosts = "localhost:29092"
      |      properties {}
      |    }
      |  }
      |
      |  admin-client {
      |    kafka-client-properties = $${kafka.ws.proxy.kafka-client.properties}
      |  }
      |
      |  consumer {
      |    default-rate-limit = 0
      |    default-batch-size = 0
      |    kafka-client-properties = $${kafka.ws.proxy.kafka-client.properties}
      |  }
      |
      |  producer {
      |    kafka-client-properties = $${kafka.ws.proxy.kafka-client.properties}
      |  }
      |
      |  session-handler {
      |    session-state-topic-name = "_wsproxy.session.state"
      |    session-state-replication-factor = 3
      |    session-state-retention = 30 days
      |  }
      |
      |  commit-handler {
      |    max-stack: 200  // wrong key
      |    auto-commit-enabled: false
      |    auto-commit-interval: 1 seconds
      |    auto-commit-max-age: 20 seconds
      |  }
      |}""".stripMargin
    )
    .resolve()

  "The Configuration" should {

    "successfully load the default configuration" in {
      val cfg = Configuration.load()

      cfg.server.serverId.value mustBe "node-1"
      cfg.server.bindInterface mustBe "0.0.0.0"
      cfg.server.port mustBe 8078

      // format: off
      cfg.kafkaClient.bootstrapHosts mustBe KafkaBootstrapHosts(List("localhost:29092")) // scalastyle:ignore
      cfg.kafkaClient.schemaRegistry.value.url mustBe "http://localhost:28081"
      cfg.kafkaClient.schemaRegistry.value.autoRegisterSchemas mustBe true
      cfg.kafkaClient.monitoringEnabled mustBe false
      // format: on

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
