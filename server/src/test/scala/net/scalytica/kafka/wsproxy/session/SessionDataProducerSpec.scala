package net.scalytica.kafka.wsproxy.session

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import io.github.embeddedkafka._
import net.scalytica.kafka.wsproxy.codecs.{
  SessionIdSerde,
  SessionSerde,
  StringBasedSerde
}
import net.scalytica.kafka.wsproxy.config.Configuration.AppCfg
import net.scalytica.kafka.wsproxy.models.WsGroupId
import net.scalytica.test.SharedAttributes.defaultTypesafeConfig
import net.scalytica.test.{WsProxySpec, WsReusableProxyKafkaFixture}
import org.apache.pekko.actor.typed.ActorSystem
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.matchers.must.Matchers
import org.scalatest.time.{Minute, Span}
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{BeforeAndAfterAll, OptionValues}

// scalastyle:off magic.number
class SessionDataProducerSpec
    extends AnyWordSpec
    with WsProxySpec
    with WsReusableProxyKafkaFixture
    with Matchers
    with OptionValues
    with Eventually
    with ScalaFutures
    with EmbeddedKafka
    with BeforeAndAfterAll {

  override protected val testTopicPrefix: String =
    "sessionhandler-producer-test-topic"

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(1, Minute))

  private[this] val atk =
    ActorTestKit("session-data-producer-test", defaultTypesafeConfig)
  implicit private[this] val sys: ActorSystem[Nothing] = atk.system

  implicit val keyDes: StringBasedSerde[SessionId] =
    new SessionIdSerde().deserializer()
  implicit val valDes: StringBasedSerde[Session] =
    new SessionSerde().deserializer()

  override def afterAll(): Unit = {
    materializer.shutdown()
    atk.shutdownTestKit()
    super.afterAll()
  }

  private[this] def testConsumerSession(i: Int): Session = {
    val grpStr = s"c$i"
    ConsumerSession(SessionId(grpStr), WsGroupId(grpStr), maxConnections = i)
  }

  private[this] val session1 = testConsumerSession(1)
  private[this] val session2 = testConsumerSession(2)
  private[this] val session3 = testConsumerSession(3)
  private[this] val session4 = testConsumerSession(4)

  "The SessionDataProducer" should {

    "be able to publish a session record to the session state topic" in
      withNoContext(useFreshStateTopics = true) { case (kcfg, appCfg) =>
        implicit val kafkaCfg: EmbeddedKafkaConfig = kcfg
        implicit val cfg: AppCfg                   = appCfg

        kafkaContext.createTopics(Map(cfg.sessionHandler.topicName.value -> 1))

        val sdp = new SessionDataProducer()
        // Write the session data to Kafka
        sdp.publish(session1)
        // Verify the data can be consumed
        val (key, value) =
          consumeFirstKeyedMessageFrom[SessionId, Session](
            cfg.sessionHandler.topicName.value
          )

        key mustBe session1.sessionId
        value.sessionId mustBe session1.sessionId
        value.maxConnections mustBe session1.maxConnections
        value.instances mustBe empty

        sdp.close()
      }

    "be able to publish multiple session records to the session state topic" in
      withNoContext(useFreshStateTopics = true) { case (kcfg, appCfg) =>
        implicit val kafkaCfg: EmbeddedKafkaConfig = kcfg
        implicit val cfg: AppCfg                   = appCfg

        kafkaContext.createTopics(Map(cfg.sessionHandler.topicName.value -> 1))

        val sdp = new SessionDataProducer()

        val expected = List(session1, session2, session3, session4)
        // Write the session data to Kafka
        expected.foreach(sdp.publish)

        val recs =
          consumeNumberKeyedMessagesFrom[SessionId, Session](
            topic = cfg.sessionHandler.topicName.value,
            number = expected.size
          )

        val keys   = recs.map(_._1)
        val values = recs.map(_._2)

        keys must contain allElementsOf expected.map(_.sessionId)
        values must contain allElementsOf expected
      }

    "be able to publish removal of a session from the session state topic" in
      withNoContext(useFreshStateTopics = true) { case (kcfg, appCfg) =>
        implicit val kafkaCfg: EmbeddedKafkaConfig = kcfg
        implicit val cfg: AppCfg                   = appCfg

        kafkaContext.createTopics(Map(cfg.sessionHandler.topicName.value -> 1))

        val sdp = new SessionDataProducer()

        val in = List(session1, session2, session3, session4)

        // Write the session data to Kafka
        in.foreach(sdp.publish)
        // Remove session2
        sdp.publishRemoval(session2.sessionId)
        // Verify the presence of all expected messages
        val r1 =
          consumeNumberKeyedMessagesFrom[SessionId, Session](
            topic = cfg.sessionHandler.topicName.value,
            number = in.size + 1
          )

        r1.map(_._2) must contain allElementsOf in
        Option(r1.lastOption.value._2) mustBe empty
      }
  }

}
