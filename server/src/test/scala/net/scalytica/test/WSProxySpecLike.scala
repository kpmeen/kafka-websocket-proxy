package net.scalytica.test

import io.confluent.kafka.serializers.AbstractKafkaAvroSerDeConfig.{
  AUTO_REGISTER_SCHEMAS,
  SCHEMA_REGISTRY_URL_CONFIG
}
import net.manub.embeddedkafka.ConsumerExtensions.ConsumerRetryConfig
import net.manub.embeddedkafka.schemaregistry.EmbeddedKafkaConfig
import net.scalytica.kafka.wsproxy.Configuration
import scala.concurrent.duration._

trait WSProxySpecLike extends FileLoader {

  // scalastyle:off magic.number
  implicit val consumerRetryConfig: ConsumerRetryConfig =
    ConsumerRetryConfig(maximumAttempts = 30, poll = 50 millis)
  // scalastyle:on magic.number

  val embeddedKafkaConfig = EmbeddedKafkaConfig(
    kafkaPort = 0,
    zooKeeperPort = 0,
    schemaRegistryPort = 0
  )

  def serverPort(port: Int): String = s"localhost:$port"

  implicit def registryConfig(
      implicit schemaRegistryPort: Int
  ): Map[String, Any] = Map(
    SCHEMA_REGISTRY_URL_CONFIG -> serverPort(schemaRegistryPort),
    AUTO_REGISTER_SCHEMAS      -> true
  )

  lazy val defaultApplicationTestConfig =
    Configuration.loadFile(filePath("/application-test.conf"))

  def applicationTestConfig(
      kafkaPort: Int,
      schemaRegistryPort: Option[Int] = None
  ): Configuration.AppCfg = defaultApplicationTestConfig.copy(
    server = defaultApplicationTestConfig.server.copy(
      kafkaBootstrapUrls = List(serverPort(kafkaPort))
    )
  )
}
