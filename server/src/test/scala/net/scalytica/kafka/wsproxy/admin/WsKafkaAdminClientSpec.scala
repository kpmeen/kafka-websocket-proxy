package net.scalytica.kafka.wsproxy.admin

import io.github.embeddedkafka.EmbeddedKafka
import net.scalytica.test.WsProxyKafkaSpec
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Minutes, Span}
import org.scalatest.OptionValues
import org.scalatest.wordspec.AnyWordSpec

class WsKafkaAdminClientSpec
    extends AnyWordSpec
    with OptionValues
    with ScalaFutures
    with WsProxyKafkaSpec
    with EmbeddedKafka {

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(2, Minutes))

  "The WsKafkaAdminClient" should {

    "return info on brokers in the cluster" in
      withRunningKafkaOnFoundPort(embeddedKafkaConfig) { implicit kcfg =>
        val wsCfg  = plainAppTestConfig(kcfg.kafkaPort)
        val client = new WsKafkaAdminClient(wsCfg)

        val res = client.clusterInfo
        res must have size 1
        res.headOption.value.id mustBe 0
        res.headOption.value.host mustBe "localhost"
        res.headOption.value.port mustBe kcfg.kafkaPort
        res.headOption.value.rack mustBe None

        client.close()
      }

    "return number replicas to use for the session topic" in
      withRunningKafkaOnFoundPort(embeddedKafkaConfig) { implicit kcfg =>
        val wsCfg  = plainAppTestConfig(kcfg.kafkaPort)
        val client = new WsKafkaAdminClient(wsCfg)

        client.replicationFactor(
          wsCfg.sessionHandler.topicName,
          wsCfg.sessionHandler.topicReplicationFactor
        ) mustBe 1

        client.close()
      }

    "return number replicas to use for the dynamic config topic" in
      withRunningKafkaOnFoundPort(embeddedKafkaConfig) { implicit kcfg =>
        val wsCfg  = plainAppTestConfig(kcfg.kafkaPort)
        val client = new WsKafkaAdminClient(wsCfg)

        client.replicationFactor(
          wsCfg.dynamicConfigHandler.topicName,
          wsCfg.dynamicConfigHandler.topicReplicationFactor
        ) mustBe 1

        client.close()
      }

    "create and find the session state topic" in
      withRunningKafkaOnFoundPort(embeddedKafkaConfig) { implicit kcfg =>
        val wsCfg  = plainAppTestConfig(kcfg.kafkaPort)
        val client = new WsKafkaAdminClient(wsCfg)

        client.initSessionStateTopic()

        val res = client.findTopic(wsCfg.sessionHandler.topicName)
        res must not be empty
        res.value mustBe wsCfg.sessionHandler.topicName.value

        client.close()
      }

    "create and find the dynamic config topic" in
      withRunningKafkaOnFoundPort(embeddedKafkaConfig) { implicit kcfg =>
        val wsCfg  = plainAppTestConfig(kcfg.kafkaPort)
        val client = new WsKafkaAdminClient(wsCfg)

        client.initDynamicConfigTopic()

        val res = client.findTopic(wsCfg.dynamicConfigHandler.topicName)
        res must not be empty
        res.value mustBe wsCfg.dynamicConfigHandler.topicName.value

        client.close()
      }
  }

}
