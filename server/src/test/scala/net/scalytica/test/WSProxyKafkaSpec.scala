package net.scalytica.test

import akka.http.scaladsl.testkit.ScalatestRouteTest
import net.manub.embeddedkafka.ConsumerExtensions.ConsumerRetryConfig
import net.manub.embeddedkafka.schemaregistry.EmbeddedKafkaConfig
import net.scalytica.kafka.wsproxy.Configuration
import org.scalatest.{MustMatchers, Suite}

import scala.concurrent.duration._

trait WSProxyKafkaSpec
    extends FileLoader
    with ScalatestRouteTest
    with MustMatchers { self: Suite =>

  // scalastyle:off magic.number
  implicit val consumerRetryConfig: ConsumerRetryConfig =
    ConsumerRetryConfig(30, 50 millis)
  // scalastyle:on magic.number

  val embeddedKafkaConfig = EmbeddedKafkaConfig(
    kafkaPort = 0,
    zooKeeperPort = 0,
    schemaRegistryPort = 0
  )

  lazy val defaultTypesafeConfig =
    FileLoader.loadConfig("/application-test.conf")

  lazy val defaultTestAppCfg =
    Configuration.loadFile(filePath("/application-test.conf"))

  lazy val defaultTestAppCfgWithServerId = (sid: Int) =>
    defaultTestAppCfg.copy(
      server = defaultTestAppCfg.server.copy(serverId = sid)
  )

  def appTestConfig(
      kafkaPort: Int,
      schemaRegistryPort: Option[Int] = None,
      serverId: Int = 1
  ): Configuration.AppCfg = defaultTestAppCfg.copy(
    server = defaultTestAppCfg.server.copy(
      serverId = serverId,
      kafkaBootstrapUrls = List(serverHost(kafkaPort)),
      schemaRegistryUrl =
        schemaRegistryPort.map(u => s"http://${serverHost(u)}")
    )
  )

}
