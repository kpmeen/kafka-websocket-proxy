package net.scalytica.kafka.wsproxy.config

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import io.github.embeddedkafka.EmbeddedKafka
import net.scalytica.kafka.wsproxy.codecs.BasicSerdes.StringDeserializer
import net.scalytica.kafka.wsproxy.codecs.DynamicCfgSerde
import net.scalytica.kafka.wsproxy.config.Configuration.{
  DynamicCfg,
  ProducerSpecificLimitCfg
}
import net.scalytica.test.WsProxyKafkaSpec
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.matchers.must.Matchers
import org.scalatest.time.{Minute, Span}
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{BeforeAndAfterAll, OptionValues}

// scalastyle:off magic.number
class DynamicConfigProducerSpec
    extends AnyWordSpec
    with WsProxyKafkaSpec
    with Matchers
    with OptionValues
    with Eventually
    with ScalaFutures
    with EmbeddedKafka
    with BeforeAndAfterAll
    with DynamicConfigTestDataGenerators {

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(1, Minute))

  val config  = defaultTypesafeConfig
  val testCfg = defaultTestAppCfg

  val testTopic = testCfg.dynamicConfigHandler.topicName

  val atk          = ActorTestKit("dyn-cfg-producer-test", config)
  implicit val sys = atk.system

  implicit val valDes = new DynamicCfgSerde().deserializer()

  override def afterAll(): Unit = {
    materializer.shutdown()
    atk.shutdownTestKit()
    super.afterAll()
  }

  "The DynamicConfigProducer" should {
    "be able to publish a dynamic config record to the topic" in {
      withRunningKafkaOnFoundPort(embeddedKafkaConfig) { implicit kcfg =>
        implicit val cfg = plainAppTestConfig(kcfg.kafkaPort)

        initTopic(testTopic.value)

        val dcp = new DynamicConfigProducer

        dcp.publishConfig(cfg1)

        val (key, value) =
          consumeFirstKeyedMessageFrom[String, DynamicCfg](testTopic.value)

        key mustBe dynamicCfgTopicKey(cfg1).value
        value mustBe a[ProducerSpecificLimitCfg]
        value.id mustBe cfg1.id
        value.asHoconString() mustBe cfg1.asHoconString()
      }
    }

    "be able to push multiple dynamic config records to the topic" in {
      withRunningKafkaOnFoundPort(embeddedKafkaConfig) { implicit kcfg =>
        implicit val cfg = plainAppTestConfig(kcfg.kafkaPort)

        initTopic(testTopic.value)

        val dcp = new DynamicConfigProducer

        expectedMap.values.foreach(dcp.publishConfig)

        val recs =
          consumeNumberKeyedMessagesFrom[String, DynamicCfg](
            topic = testTopic.value,
            number = expectedMap.size
          )

        recs.toMap must contain allElementsOf expectedMap
      }
    }

    "be able to push removal of a dynamic config from the topic" in {
      withRunningKafkaOnFoundPort(embeddedKafkaConfig) { implicit kcfg =>
        implicit val cfg = plainAppTestConfig(kcfg.kafkaPort)
        val theKey       = dynamicCfgTopicKey(cfg3).value

        initTopic(testTopic.value)

        val dcp = new DynamicConfigProducer

        expectedMap.values.foreach(dcp.publishConfig)
        dcp.removeConfig(theKey)

        val recs =
          consumeNumberKeyedMessagesFrom[String, DynamicCfg](
            topic = testTopic.value,
            number = expectedMap.size + 1
          )

        recs.map(_._2) must contain allElementsOf expectedMap.values

        val remRecord = recs.lastOption.value
        remRecord._1 mustBe theKey
        Option(remRecord._2) mustBe empty
      }
    }
  }
}
