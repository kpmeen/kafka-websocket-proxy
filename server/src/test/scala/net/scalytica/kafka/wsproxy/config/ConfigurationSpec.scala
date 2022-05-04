package net.scalytica.kafka.wsproxy.config

import com.typesafe.config.{ConfigFactory, ConfigValueFactory}
import net.scalytica.kafka.wsproxy.config.Configuration.{
  AppCfg,
  ClientLimitsCfg,
  ConsumerSpecificLimitCfg,
  KafkaBootstrapHosts
}
import net.scalytica.kafka.wsproxy.errors.ConfigurationError
import net.scalytica.kafka.wsproxy.models.WsGroupId
import net.scalytica.test.FileLoader.testConfigPath
import org.scalatest.OptionValues
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec
import pureconfig.ConfigSource
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
      |
      |    admin {
      |      enabled = true
      |      bind-interface = "0.0.0.0"
      |      port = 9078
      |    }
      |
      |    jmx {
      |      manager {
      |        proxy.status.interval = 5 seconds
      |      }
      |    }
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
      |    limits {
      |      default-messages-per-second = 0
      |      default-max-connections-per-client = 0
      |      default-batch-size = 0
      |      client-specific-limits: []
      |    }
      |    kafka-client-properties = $${kafka.ws.proxy.kafka-client.properties}
      |  }
      |
      |  producer {
      |    limits {
      |      default-messages-per-second = 0
      |      default-max-connections-per-client = 0
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
      |
      |    admin {
      |      enabled = true
      |      bind-interface = "0.0.0.0"
      |      port = 9078
      |    }
      |
      |    jmx {
      |      manager {
      |        proxy.status.interval = 5 seconds
      |      }
      |    }
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
      |    limits {
      |      default-messages-per-second = 0
      |      default-max-connections-per-client = 0
      |      default-batch-size = 0
      |      client-specific-limits: []
      |    }
      |    kafka-client-properties = $${kafka.ws.proxy.kafka-client.properties}
      |  }
      |
      |  producer {
      |    limits {
      |      default-messages-per-second = 0
      |      default-max-connections-per-client = 0
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

    "successfully parse a ClientLimitsCfg with consumer specific limit cfg" in {
      import pureconfig.generic.auto._
      val cfg = ConfigSource.string(
        """
          |{
          |  default-messages-per-second = 0
          |  default-max-connections-per-client = 0
          |  default-batch-size = 0
          |  client-specific-limits: [
          |    {
          |      group-id = "group1",
          |      messages-per-second = 0
          |      max-connections = 2
          |      batch-size = 10
          |    }
          |  ]
          |}""".stripMargin
      )

      val res = cfg.loadOrThrow[ClientLimitsCfg]
      res.defaultMessagesPerSecond mustBe 0
      res.defaultMaxConnectionsPerClient mustBe 0
      res.defaultBatchSize mustBe 0
      res.clientSpecificLimits.size mustBe 1
      res.forConsumer(WsGroupId("group1")) match {
        case Some(csl: ConsumerSpecificLimitCfg) =>
          csl.groupId mustBe WsGroupId("group1")
          csl.messagesPerSecond.value mustBe 0
          csl.maxConnections.value mustBe 2
          csl.batchSize.value mustBe 10

        case _ =>
          fail("Unexpected client specific limits config type")
      }
    }

    "successfully load the application-test.conf configuration" in {
      val cfg = Configuration.loadFile(testConfigPath)

      cfg.server.serverId.value mustBe "node-1"
      cfg.server.bindInterface mustBe "0.0.0.0"
      cfg.server.port mustBe 8078
      cfg.server.secureHealthCheckEndpoint mustBe true

      cfg.server.admin.enabled mustBe true
      cfg.server.admin.bindInterface mustBe "0.0.0.0"
      cfg.server.admin.port mustBe 9078

      val kcCfg = cfg.kafkaClient
      kcCfg.bootstrapHosts mustBe KafkaBootstrapHosts(List("localhost:29092"))
      kcCfg.schemaRegistry.value.url mustBe "http://localhost:28081"
      kcCfg.schemaRegistry.value.autoRegisterSchemas mustBe true
      kcCfg.monitoringEnabled mustBe false

      cfg.consumer.limits.defaultBatchSize mustBe 0
      cfg.consumer.limits.defaultMessagesPerSecond mustBe 0

      cfg.producer.limits.defaultMessagesPerSecond mustBe 0
      cfg.producer.limits.defaultMaxConnectionsPerClient mustBe 0
      cfg.producer.limits.clientSpecificLimits must have size 2
      val limitedClient1Cfg =
        cfg.producer.limits.clientSpecificLimits.headOption.value
      limitedClient1Cfg.id mustBe "limit-test-producer-1"
      limitedClient1Cfg.messagesPerSecond.value mustBe 10
      limitedClient1Cfg.maxConnections.value mustBe 1
      val limitedClient2Cfg =
        cfg.producer.limits.clientSpecificLimits.lastOption.value
      limitedClient2Cfg.id mustBe "limit-test-producer-2"
      limitedClient2Cfg.messagesPerSecond.value mustBe 10
      limitedClient2Cfg.maxConnections.value mustBe 2

      val shCfg = cfg.sessionHandler
      shCfg.sessionStateTopicName.value mustBe "_wsproxy.session.state"
      shCfg.sessionStateReplicationFactor mustBe 3
      shCfg.sessionStateRetention mustBe 30.days

      val chCfg = cfg.commitHandler
      chCfg.maxStackSize mustBe 20
      chCfg.autoCommitEnabled mustBe false
      chCfg.autoCommitInterval mustBe 1.second
      chCfg.autoCommitMaxAge mustBe 20.seconds
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

        val kcCfg = cfg.kafkaClient
        kcCfg.bootstrapHosts mustBe KafkaBootstrapHosts(List("localhost:29092"))
        kcCfg.schemaRegistry mustBe empty
        kcCfg.monitoringEnabled mustBe false

        cfg.consumer.limits.defaultBatchSize mustBe 0
        cfg.consumer.limits.defaultMessagesPerSecond mustBe 0

        cfg.producer.limits.defaultMessagesPerSecond mustBe 0
        cfg.producer.limits.defaultMaxConnectionsPerClient mustBe 0
        cfg.producer.limits.clientSpecificLimits mustBe empty

        val shCfg = cfg.sessionHandler
        shCfg.sessionStateTopicName.value mustBe "_wsproxy.session.state"
        shCfg.sessionStateReplicationFactor mustBe 3
        shCfg.sessionStateRetention mustBe 30.days

        val chCfg = cfg.commitHandler
        chCfg.maxStackSize mustBe 100
        chCfg.autoCommitEnabled mustBe false
        chCfg.autoCommitInterval mustBe 1.second
        chCfg.autoCommitMaxAge mustBe 20.seconds
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

      cfg.server.admin.enabled mustBe false
      cfg.server.admin.bindInterface mustBe "0.0.0.0"
      cfg.server.admin.port mustBe 9078

      val kcCfg = cfg.kafkaClient
      kcCfg.bootstrapHosts mustBe KafkaBootstrapHosts(List("localhost:29092"))
      kcCfg.schemaRegistry.value.url mustBe "http://localhost:28081"
      kcCfg.schemaRegistry.value.autoRegisterSchemas mustBe true
      kcCfg.monitoringEnabled mustBe false

      cfg.consumer.limits.defaultBatchSize mustBe 0
      cfg.consumer.limits.defaultMessagesPerSecond mustBe 0

      cfg.producer.limits.defaultMessagesPerSecond mustBe 0
      cfg.producer.limits.defaultMaxConnectionsPerClient mustBe 0
      cfg.producer.limits.clientSpecificLimits mustBe empty

      val shCfg = cfg.sessionHandler
      shCfg.sessionStateTopicName.value mustBe "_wsproxy.session.state"
      shCfg.sessionStateReplicationFactor mustBe 3
      shCfg.sessionStateRetention mustBe 30.days

      val chCfg = cfg.commitHandler
      chCfg.maxStackSize mustBe 100
      chCfg.autoCommitEnabled mustBe false
      chCfg.autoCommitInterval mustBe 1.second
      chCfg.autoCommitMaxAge mustBe 20.seconds
    }

    "successfully load the default configuration with admin enabled on " +
      "different bind interface" in {
        val tcfg = ConfigFactory.defaultApplication
          .withValue(
            "kafka.ws.proxy.kafka-client.bootstrap-hosts",
            ConfigValueFactory.fromAnyRef("localhost:29092")
          )
          .withValue(
            "kafka.ws.proxy.kafka-client.schema-registry.url",
            ConfigValueFactory.fromAnyRef("http://localhost:28081")
          )
          .withValue(
            "kafka.ws.proxy.server.admin.enabled",
            ConfigValueFactory.fromAnyRef(true)
          )
          .withValue(
            "kafka.ws.proxy.server.admin.bind-interface",
            ConfigValueFactory.fromAnyRef("127.0.0.1")
          )

        val cfg = Configuration.loadConfig(tcfg)

        cfg.server.admin.enabled mustBe true
        cfg.server.admin.bindInterface mustBe "127.0.0.1"
        cfg.server.admin.port mustBe 9078
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
