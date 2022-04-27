package net.scalytica.kafka.wsproxy.session

import akka.actor.testkit.typed.scaladsl.ActorTestKit
import io.github.embeddedkafka._
import net.scalytica.kafka.wsproxy.codecs.{SessionIdSerde, SessionSerde}
import net.scalytica.kafka.wsproxy.models.WsGroupId
import net.scalytica.test.WsProxyKafkaSpec
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.time.{Minute, Span}
import org.scalatest.{BeforeAndAfterAll, OptionValues}
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec

// scalastyle:off magic.number
class SessionDataProducerSpec
    extends AnyWordSpec
    with WsProxyKafkaSpec
    with Matchers
    with OptionValues
    with Eventually
    with ScalaFutures
    with EmbeddedKafka
    with BeforeAndAfterAll {

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(1, Minute))

  val config  = defaultTypesafeConfig
  val testCfg = defaultTestAppCfg

  val sessionTopic = testCfg.sessionHandler.sessionStateTopicName

  val atk          = ActorTestKit("session-data-producer-test", config)
  implicit val sys = atk.system

  implicit val keyDes = new SessionIdSerde().deserializer()
  implicit val valDes = new SessionSerde().deserializer()

  override def afterAll(): Unit = {
    atk.shutdownTestKit()
    super.afterAll()
  }

  private[this] def testConsumerSession(i: Int): Session = {
    val grpStr = s"c$i"
    ConsumerSession(SessionId(grpStr), WsGroupId(grpStr), maxConnections = i)
  }

  val session1 = testConsumerSession(1)
  val session2 = testConsumerSession(2)
  val session3 = testConsumerSession(3)
  val session4 = testConsumerSession(4)

  "The SessionDataProducer" should {

    "be able to publish a session record to the session state topic" in
      withRunningKafkaOnFoundPort(embeddedKafkaConfig) { implicit kcfg =>
        implicit val cfg = plainAppTestConfig(kcfg.kafkaPort)

        initTopic(cfg.sessionHandler.sessionStateTopicName.value)

        val sdp = new SessionDataProducer()
        // Write the session data to Kafka
        sdp.publish(session1)
        // Verify the data can be consumed
        val (key, value) =
          consumeFirstKeyedMessageFrom[SessionId, Session](
            sessionTopic.value
          )

        key mustBe session1.sessionId
        value.sessionId mustBe session1.sessionId
        value.maxConnections mustBe session1.maxConnections
        value.instances mustBe empty

        sdp.close()
      }

    "be able to publish multiple session records to the session state topic" in
      withRunningKafkaOnFoundPort(embeddedKafkaConfig) { implicit kcfg =>
        implicit val cfg = plainAppTestConfig(kcfg.kafkaPort)

        initTopic(cfg.sessionHandler.sessionStateTopicName.value)

        val sdp = new SessionDataProducer()

        val expected = List(session1, session2, session3, session4)
        // Write the session data to Kafka
        expected.foreach(s => sdp.publish(s))

        val recs =
          consumeNumberKeyedMessagesFrom[SessionId, Session](
            topic = sessionTopic.value,
            number = 4
          )

        val keys   = recs.map(_._1)
        val values = recs.map(_._2)

        keys must contain allElementsOf expected.map(_.sessionId)
        values must contain allElementsOf expected
      }

    "be able to publish removal of a session from the session state topic" in
      withRunningKafkaOnFoundPort(embeddedKafkaConfig) { implicit kcfg =>
        implicit val cfg = plainAppTestConfig(kcfg.kafkaPort)

        initTopic(cfg.sessionHandler.sessionStateTopicName.value)

        val sdp = new SessionDataProducer()

        val in = List(session1, session2, session3, session4)

        // Write the session data to Kafka
        in.foreach(s => sdp.publish(s))
        // Remove session2
        sdp.publishRemoval(session2.sessionId)
        // Verify the presence of all expected messages
        val r1 =
          consumeNumberKeyedMessagesFrom[SessionId, Session](
            topic = sessionTopic.value,
            number = 5
          )

        r1.map(_._2) must contain allElementsOf in
        Option(r1.lastOption.value._2) mustBe empty
      }
  }

}
