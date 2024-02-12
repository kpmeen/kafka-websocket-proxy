package net.scalytica.kafka.wsproxy.admin

import io.github.embeddedkafka.EmbeddedKafka
import net.scalytica.test.{WsProxySpec, WsReusableProxyKafkaFixture}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Minutes, Span}
import org.scalatest.OptionValues
import org.scalatest.wordspec.AnyWordSpec

class WsKafkaAdminClientSpec
    extends AnyWordSpec
    with OptionValues
    with ScalaFutures
    with WsProxySpec
    with WsReusableProxyKafkaFixture
    with EmbeddedKafka {

  override protected val testTopicPrefix: String = "kafka-admin-test-topic"

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(2, Minutes))

  "The WsKafkaAdminClient" should {

    "return info on brokers in the cluster" in
      withNoContext() { case (kCfg, wsCfg) =>
        val client = new WsKafkaAdminClient(wsCfg)

        val res = client.clusterInfo
        res must have size 1
        res.headOption.value.id mustBe 0
        res.headOption.value.host mustBe "localhost"
        res.headOption.value.port mustBe kCfg.kafkaPort
        res.headOption.value.rack mustBe None

        client.close()
      }

    "return number replicas to use for the session topic" in
      withNoContext() { case (_, wsCfg) =>
        val client = new WsKafkaAdminClient(wsCfg)

        client.replicationFactor(
          wsCfg.sessionHandler.topicName,
          wsCfg.sessionHandler.topicReplicationFactor
        ) mustBe 1

        client.close()
      }

    "return number replicas to use for the dynamic config topic" in
      withNoContext() { case (_, wsCfg) =>
        val client = new WsKafkaAdminClient(wsCfg)

        client.replicationFactor(
          wsCfg.dynamicConfigHandler.topicName,
          wsCfg.dynamicConfigHandler.topicReplicationFactor
        ) mustBe 1

        client.close()
      }

    "create and find the session state topic" in
      withNoContext() { case (_, wsCfg) =>
        val client = new WsKafkaAdminClient(wsCfg)

        client.initSessionStateTopic()

        val res = client.findTopic(wsCfg.sessionHandler.topicName)
        res must not be empty
        res.value mustBe wsCfg.sessionHandler.topicName.value

        client.close()
      }

    "create and find the dynamic config topic" in
      withNoContext() { case (_, wsCfg) =>
        val client = new WsKafkaAdminClient(wsCfg)

        client.initDynamicConfigTopic()

        val res = client.findTopic(wsCfg.dynamicConfigHandler.topicName)
        res must not be empty
        res.value mustBe wsCfg.dynamicConfigHandler.topicName.value

        client.close()
      }
  }

}
