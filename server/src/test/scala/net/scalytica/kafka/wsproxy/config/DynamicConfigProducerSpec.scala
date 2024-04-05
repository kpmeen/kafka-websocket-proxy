package net.scalytica.kafka.wsproxy.config

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import io.github.embeddedkafka.EmbeddedKafka
import net.scalytica.kafka.wsproxy.codecs.BasicSerdes.StringDeserializer
import net.scalytica.kafka.wsproxy.codecs.{DynamicCfgSerde, StringBasedSerde}
import net.scalytica.kafka.wsproxy.config
import net.scalytica.kafka.wsproxy.config.Configuration.{
  DynamicCfg,
  ProducerSpecificLimitCfg
}
import net.scalytica.test.SharedAttributes.defaultTypesafeConfig
import net.scalytica.test.{WsProxySpec, WsReusableProxyKafkaFixture}
import org.apache.pekko.actor.typed.ActorSystem
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.matchers.must.Matchers
import org.scalatest.time.{Minute, Span}
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{BeforeAndAfterAll, OptionValues}

// scalastyle:off magic.number
class DynamicConfigProducerSpec
    extends AnyWordSpec
    with WsProxySpec
    with WsReusableProxyKafkaFixture
    with Matchers
    with OptionValues
    with Eventually
    with ScalaFutures
    with EmbeddedKafka
    with BeforeAndAfterAll
    with DynamicConfigTestDataGenerators {

  override protected val testTopicPrefix: String = "dynamic-producer-test-topic"

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(1, Minute))

  val atk: ActorTestKit =
    ActorTestKit("dyn-cfg-producer-test", defaultTypesafeConfig)
  implicit val sys: ActorSystem[Nothing] = atk.system

  implicit val valDes: StringBasedSerde[config.Configuration.DynamicCfg] =
    new DynamicCfgSerde().deserializer()

  override def afterAll(): Unit = {
    materializer.shutdown()
    atk.shutdownTestKit()
    super.afterAll()
  }

  "The DynamicConfigProducer" should {
    "be able to publish a dynamic config record to the topic" in {
      withNoContext(useFreshStateTopics = true) { case (ekCfg, wsCfg) =>
        implicit val kcfg = ekCfg
        implicit val cfg  = wsCfg

        kafkaContext.createTopics(
          Map(cfg.dynamicConfigHandler.topicName.value -> 1)
        )

        val dcp = new DynamicConfigProducer

        dcp.publishConfig(cfg1)

        val (key, value) =
          consumeFirstKeyedMessageFrom[String, DynamicCfg](
            cfg.dynamicConfigHandler.topicName.value
          )

        key mustBe dynamicCfgTopicKey(cfg1).value
        value mustBe a[ProducerSpecificLimitCfg]
        value.id mustBe cfg1.id
        value.asHoconString() mustBe cfg1.asHoconString()
      }
    }

    "be able to push multiple dynamic config records to the topic" in {
      withNoContext(useFreshStateTopics = true) { case (ekCfg, wsCfg) =>
        implicit val kcfg = ekCfg
        implicit val cfg  = wsCfg

        kafkaContext.createTopics(
          Map(cfg.dynamicConfigHandler.topicName.value -> 1)
        )

        val dcp = new DynamicConfigProducer

        expectedMap.values.foreach(dcp.publishConfig)

        val recs =
          consumeNumberKeyedMessagesFrom[String, DynamicCfg](
            topic = cfg.dynamicConfigHandler.topicName.value,
            number = expectedMap.size
          )

        recs.toMap must contain allElementsOf expectedMap
      }
    }

    "be able to push removal of a dynamic config from the topic" in {
      withNoContext(useFreshStateTopics = true) { case (ekCfg, wsCfg) =>
        implicit val kcfg = ekCfg
        implicit val cfg  = wsCfg

        kafkaContext.createTopics(
          Map(cfg.dynamicConfigHandler.topicName.value -> 1)
        )

        val dcp = new DynamicConfigProducer

        val theKey = dynamicCfgTopicKey(cfg3).value

        expectedMap.values.foreach(dcp.publishConfig)
        dcp.removeConfig(theKey)

        val recs =
          consumeNumberKeyedMessagesFrom[String, DynamicCfg](
            topic = cfg.dynamicConfigHandler.topicName.value,
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
