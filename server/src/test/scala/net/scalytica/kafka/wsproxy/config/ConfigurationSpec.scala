package net.scalytica.kafka.wsproxy.config

import com.typesafe.config.{ConfigFactory, ConfigValueFactory}
import net.scalytica.kafka.wsproxy.config.Configuration.{
  AppCfg,
  KafkaBootstrapHosts
}
import net.scalytica.kafka.wsproxy.errors.ConfigurationError
import net.scalytica.test.FileLoader.testConfigPath
import org.scalatest.OptionValues
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec
import pureconfig.error.ConfigReaderException

import scala.concurrent.duration._

class ConfigurationSpec extends AnyWordSpec with Matchers with OptionValues {

  lazy val invalidCfg1 = ConfigFactory
    .parseString(
      s"""kafka.ws.proxy {
      |  server {
      |    server-id = "node-1"
      |    bind-interface = "localhost"
      |    // port = 8078 // missing key
      |    secure-health-check-endpoint = true
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
      |    default-rate-limit-messages-per-second = 0
      |    default-batch-size = 0
      |    kafka-client-properties = $${kafka.ws.proxy.kafka-client.properties}
      |  }
      |
      |  producer {
      |    rate-limit {
      |      default-messages-per-second = 0
      |      client-limits: []
      |    }
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

  lazy val invalidCfg2 = ConfigFactory
    .parseString(
      s"""kafka.ws.proxy {
      |  server {
      |    serverId = "node-1" // wrong key
      |    bind-interface = "localhost"
      |    port = 8078
      |    secure-health-check-endpoint = true
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
      |    default-rate-limit-messages-per-second = 0
      |    default-batch-size = 0
      |    kafka-client-properties = $${kafka.ws.proxy.kafka-client.properties}
      |  }
      |
      |  producer {
      |    rate-limit {
      |      default-messages-per-second = 0
      |      client-limits: []
      |    }
      |    kafka-client-properties = $${kafka.ws.proxy.kafka-client.properties} {
      |      delivery.timeout.ms = 20000
      |    }
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

    "successfully load the application-test.conf configuration" in {
      val cfg = Configuration.loadFile(testConfigPath)

      cfg.server.serverId.value mustBe "node-1"
      cfg.server.bindInterface mustBe "0.0.0.0"
      cfg.server.port mustBe 8078
      cfg.server.secureHealthCheckEndpoint mustBe true

      // format: off
      cfg.kafkaClient.bootstrapHosts mustBe KafkaBootstrapHosts(List("localhost:29092")) // scalastyle:ignore
      cfg.kafkaClient.schemaRegistry.value.url mustBe "http://localhost:28081"
      cfg.kafkaClient.schemaRegistry.value.autoRegisterSchemas mustBe true
      cfg.kafkaClient.monitoringEnabled mustBe false
      // format: on

      cfg.consumer.defaultBatchSize mustBe 0
      cfg.consumer.defaultRateLimitMessagesPerSecond mustBe 0

      cfg.sessionHandler.sessionStateTopicName.value mustBe "_wsproxy.session.state" // scalastyle:ignore
      cfg.sessionHandler.sessionStateReplicationFactor mustBe 3
      cfg.sessionHandler.sessionStateRetention mustBe 30.days

      cfg.commitHandler.maxStackSize mustBe 20
      cfg.commitHandler.autoCommitEnabled mustBe false
      cfg.commitHandler.autoCommitInterval mustBe 1.second
      cfg.commitHandler.autoCommitMaxAge mustBe 20.seconds
    }

    "successfully load the default configuration without the " +
      "schema-registry-url key set" in {
        val tcfg = ConfigFactory.defaultApplication.withValue(
          "kafka.ws.proxy.kafka-client.bootstrap-hosts",
          ConfigValueFactory.fromAnyRef("localhost:29092")
        )

        val cfg = Configuration.loadConfig(tcfg)

        cfg.server.serverId.value mustBe "node-1"
        cfg.server.bindInterface mustBe "0.0.0.0"
        cfg.server.port mustBe 8078
        cfg.server.secureHealthCheckEndpoint mustBe true

        // format: off
        cfg.kafkaClient.bootstrapHosts mustBe KafkaBootstrapHosts(List("localhost:29092")) // scalastyle:ignore
        cfg.kafkaClient.schemaRegistry mustBe empty
        cfg.kafkaClient.monitoringEnabled mustBe false
        // format: on

        cfg.consumer.defaultBatchSize mustBe 0
        cfg.consumer.defaultRateLimitMessagesPerSecond mustBe 0

        cfg.sessionHandler.sessionStateTopicName.value mustBe "_wsproxy.session.state" // scalastyle:ignore
        cfg.sessionHandler.sessionStateReplicationFactor mustBe 3
        cfg.sessionHandler.sessionStateRetention mustBe 30.days

        cfg.commitHandler.maxStackSize mustBe 100
        cfg.commitHandler.autoCommitEnabled mustBe false
        cfg.commitHandler.autoCommitInterval mustBe 1.second
        cfg.commitHandler.autoCommitMaxAge mustBe 20.seconds
      }

    "successfully load the application.conf configuration" in {
      // Set required, unset, props
      val tcfg = ConfigFactory.defaultApplication
        .withValue(
          "kafka.ws.proxy.kafka-client.bootstrap-hosts",
          ConfigValueFactory.fromAnyRef("localhost:29092")
        )
        .withValue(
          "kafka.ws.proxy.kafka-client.schema-registry.url",
          ConfigValueFactory.fromAnyRef("http://localhost:28081")
        )
      val cfg = Configuration.loadConfig(tcfg)

      cfg.server.serverId.value mustBe "node-1"
      cfg.server.bindInterface mustBe "0.0.0.0"
      cfg.server.port mustBe 8078
      cfg.server.secureHealthCheckEndpoint mustBe true

      // format: off
      cfg.kafkaClient.bootstrapHosts mustBe KafkaBootstrapHosts(List("localhost:29092")) // scalastyle:ignore
      cfg.kafkaClient.schemaRegistry.value.url mustBe "http://localhost:28081"
      cfg.kafkaClient.schemaRegistry.value.autoRegisterSchemas mustBe true
      cfg.kafkaClient.monitoringEnabled mustBe false
      // format: on

      cfg.consumer.defaultBatchSize mustBe 0
      cfg.consumer.defaultRateLimitMessagesPerSecond mustBe 0

      cfg.sessionHandler.sessionStateTopicName.value mustBe "_wsproxy.session.state" // scalastyle:ignore
      cfg.sessionHandler.sessionStateReplicationFactor mustBe 3
      cfg.sessionHandler.sessionStateRetention mustBe 30.days

      cfg.commitHandler.maxStackSize mustBe 100
      cfg.commitHandler.autoCommitEnabled mustBe false
      cfg.commitHandler.autoCommitInterval mustBe 1.second
      cfg.commitHandler.autoCommitMaxAge mustBe 20.seconds
    }

    "successfully load the default configuration with the custom JWT Kafka " +
      "credential keys set" in {
        val tcfg = ConfigFactory.defaultApplication
          .withValue(
            "kafka.ws.proxy.kafka-client.bootstrap-hosts",
            ConfigValueFactory.fromAnyRef("localhost:29092")
          )
          .withValue(
            "kafka.ws.proxy.server.openid-connect.custom-jwt.jwt-kafka-username-key", // scalastyle:ignore
            ConfigValueFactory.fromAnyRef("test.username.key")
          )
          .withValue(
            "kafka.ws.proxy.server.openid-connect.custom-jwt.jwt-kafka-password-key", // scalastyle:ignore
            ConfigValueFactory.fromAnyRef("test.password.key")
          )
        val cfg    = Configuration.loadConfig(tcfg)
        val jwtCfg = cfg.server.openidConnect.value.customJwt.value

        jwtCfg.jwtKafkaUsernameKey mustBe "test.username.key"
        jwtCfg.jwtKafkaPasswordKey mustBe "test.password.key"
      }

    "not assign a value to the custom jwt config when one of the keys have " +
      "no value" in {
        val tcfg = ConfigFactory.defaultApplication
          .withValue(
            "kafka.ws.proxy.kafka-client.bootstrap-hosts",
            ConfigValueFactory.fromAnyRef("localhost:29092")
          )
          .withValue(
            "kafka.ws.proxy.server.openid-connect.custom-jwt.jwt-kafka-username-key", // scalastyle:ignore
            ConfigValueFactory.fromAnyRef("test.username.key")
          )
        val cfg = Configuration.loadConfig(tcfg)
        cfg.server.openidConnect.value.customJwt mustBe None
      }

    "fail when trying to load the default config without providing the " +
      "bootstrap-hosts with a value" in {
        a[ConfigurationError] should be thrownBy Configuration
          .loadTypesafeConfig()
      }

    "fail when trying to load an invalid configuration" in {
      a[ConfigReaderException[AppCfg]] should be thrownBy Configuration
        .loadConfig(invalidCfg1)

      a[ConfigReaderException[AppCfg]] should be thrownBy Configuration
        .loadConfig(invalidCfg2)
    }

    "fail initialising an enabled BasicAuthCfg without required params" in {
      val tcfg = ConfigFactory.defaultApplication.withValue(
        "kafka.ws.proxy.server.basic-auth",
        ConfigValueFactory.fromAnyRef("true")
      )
      a[ConfigReaderException[AppCfg]] should be thrownBy Configuration
        .loadConfig(tcfg)
    }

  }

}
