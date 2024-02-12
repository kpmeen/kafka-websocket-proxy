package net.scalytica.kafka.wsproxy.config

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.stream.scaladsl.Sink
import io.github.embeddedkafka.{EmbeddedKafka, EmbeddedKafkaConfig}
import net.scalytica.kafka.wsproxy.NiceClassNameExtensions
import net.scalytica.kafka.wsproxy.codecs.BasicSerdes.StringSerializer
import net.scalytica.kafka.wsproxy.codecs.DynamicCfgSerde
import net.scalytica.kafka.wsproxy.config.Configuration.DynamicCfg
import net.scalytica.kafka.wsproxy.config.DynamicConfigHandlerProtocol.{
  RemoveDynamicConfigRecord,
  UpdateDynamicConfigRecord
}
import net.scalytica.kafka.wsproxy.models.TopicName
import net.scalytica.test.SharedAttributes.defaultTypesafeConfig
import net.scalytica.test.{WsProxySpec, WsReusableProxyKafkaFixture}
import org.apache.kafka.common.serialization.Serializer
import org.scalatest.Inspectors.forAll
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.matchers.must.Matchers
import org.scalatest.time.{Minute, Span}
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{BeforeAndAfterAll, OptionValues}

class DynamicConfigConsumerSpec
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

  override protected val testTopicPrefix: String = "dynamic-consumer-test-topic"

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(1, Minute))

  val atk = ActorTestKit("dyn-cfg-producer-test", defaultTypesafeConfig)
  implicit val sys = atk.system

  val dcfgSerde = new DynamicCfgSerde()

  implicit val valSer: Serializer[DynamicCfg] = dcfgSerde.serializer()

  override def afterAll(): Unit = {
    materializer.shutdown()
    atk.shutdownTestKit()
    super.afterAll()
  }

  private[this] def publish(
      testTopic: TopicName,
      dcfg: DynamicCfg
  )(implicit ekcfg: EmbeddedKafkaConfig): Unit = {
    publishToKafka[String, DynamicCfg](
      topic = testTopic.value,
      key = dynamicCfgTopicKey(dcfg).value,
      message = dcfg
    )
  }

  private[this] def publishTombstone(
      testTopic: TopicName,
      key: String
  )(implicit ekcfg: EmbeddedKafkaConfig): Unit = {
    publishToKafka[String, DynamicCfg](
      topic = testTopic.value,
      key = key,
      message = null // scalastyle:ignore
    )
  }

  "The DynamicConfigConsumer" should {
    "consume dynamic config data from the Kafka topic" in {
      withNoContext(useFreshStateTopics = true) { case (ekCfg, wsCfg) =>
        implicit val kcfg = ekCfg
        implicit val cfg  = wsCfg

        kafkaContext.createTopics(
          Map(cfg.dynamicConfigHandler.topicName.value -> 1)
        )

        val expected = expectedMap.values

        expected.foreach(d => publish(cfg.dynamicConfigHandler.topicName, d))

        val dcc = new DynamicConfigConsumer()
        val recs =
          dcc.dynamicCfgSource
            .take(expected.size.toLong)
            .runWith(Sink.seq)
            .futureValue

        forAll(recs) {
          case UpdateDynamicConfigRecord(key, value, _) =>
            expectedMap.keys.exists(_.equals(key)) mustBe true
            expectedMap.get(key).value mustBe value

          case RemoveDynamicConfigRecord(key, offset) =>
            fail(s"Unexpected tombstone for $key at offset $offset")
        }
      }
    }

    "correctly handle tombstone messages" in {
      withNoContext(useFreshStateTopics = true) { case (ekCfg, wsCfg) =>
        implicit val kcfg = ekCfg
        implicit val cfg  = wsCfg

        kafkaContext.createTopics(
          Map(cfg.dynamicConfigHandler.topicName.value -> 1)
        )

        val theKey = dynamicCfgTopicKey(cfg1).value

        publish(cfg.dynamicConfigHandler.topicName, cfg1)
        publishTombstone(cfg.dynamicConfigHandler.topicName, theKey)

        val dcc = new DynamicConfigConsumer()
        val recs =
          dcc.dynamicCfgSource.take(2).runWith(Sink.seq).futureValue

        recs.headOption.value match {
          case UpdateDynamicConfigRecord(key, value, offset) =>
            key mustBe theKey
            value mustBe cfg1
            offset mustBe 0

          case wrong =>
            fail(
              "Expected an " +
                s"${classOf[UpdateDynamicConfigRecord].niceClassNameShort} " +
                s"but got a ${wrong.getClass.niceClassNameShort}"
            )
        }

        recs.lastOption.value match {
          case RemoveDynamicConfigRecord(key, offset) =>
            key mustBe theKey
            offset mustBe 1

          case wrong =>
            fail(
              "Expected an " +
                s"${classOf[RemoveDynamicConfigRecord].niceClassNameShort} " +
                s"but got a ${wrong.getClass.niceClassNameShort}"
            )
        }
      }
    }
  }
}
