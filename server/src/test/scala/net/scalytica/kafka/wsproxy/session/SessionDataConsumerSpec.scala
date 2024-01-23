package net.scalytica.kafka.wsproxy.session

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.stream.scaladsl.Sink
import io.github.embeddedkafka.{EmbeddedKafka, EmbeddedKafkaConfig}
import net.scalytica.kafka.wsproxy.codecs.{
  BasicSerdes,
  SessionIdSerde,
  SessionSerde
}
import net.scalytica.kafka.wsproxy.models.WsGroupId
import net.scalytica.kafka.wsproxy.session.SessionHandlerProtocol.{
  ClientSessionProtocol,
  InternalSessionProtocol,
  RemoveSession,
  UpdateSession
}
import net.scalytica.test.WsProxyKafkaSpec
import org.scalatest.Inspectors.forAll
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.matchers.must.Matchers
import org.scalatest.time.{Minute, Span}
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{BeforeAndAfterAll, OptionValues}

// scalastyle:off magic.number
class SessionDataConsumerSpec
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

  val sessionTopic = testCfg.sessionHandler.topicName

  val atk = ActorTestKit("session-data-consumer-test", config)

  implicit val sys = atk.system

  implicit val sessionIdSerde = new SessionIdSerde()
  implicit val sessionSerde   = new SessionSerde()
  implicit val keyDes         = BasicSerdes.StringDeserializer
  implicit val keySer         = BasicSerdes.StringSerializer

  override def afterAll(): Unit = {
    materializer.shutdown()
    atk.shutdownTestKit()
    super.afterAll()
  }

  private[this] def publish(
      s: Session
  )(implicit config: EmbeddedKafkaConfig): Unit = {
    publishToKafka[SessionId, Session](
      topic = sessionTopic.value,
      key = s.sessionId,
      message = s
    )
  }

  private[this] def publishTombstone(
      sid: SessionId
  )(implicit config: EmbeddedKafkaConfig): Unit = {
    publishToKafka[SessionId, Session](
      topic = sessionTopic.value,
      key = sid,
      message = null // scalastyle:ignore
    )
  }

  private[this] def testConsumerSession(i: Int): Session = {
    val grpStr = s"c$i"
    ConsumerSession(SessionId(grpStr), WsGroupId(grpStr), maxConnections = i)
  }

  val session1 = testConsumerSession(1)
  val session2 = testConsumerSession(2)
  val session3 = testConsumerSession(3)
  val session4 = testConsumerSession(4)

  "The SessionConsumer" should {

    "consume session data from the session state topic" in {
      withRunningKafkaOnFoundPort(embeddedKafkaConfig) { implicit kcfg =>
        implicit val cfg = plainAppTestConfig(kcfg.kafkaPort)

        initTopic(cfg.sessionHandler.topicName.value)

        val expected = List(session1, session2, session3, session4)

        // Prepare the topic with some messages
        expected.foreach(publish)
        // Publish tombstone for session2
        publishTombstone(session2.sessionId)

        val sdc  = new SessionDataConsumer()
        val recs = sdc.sessionStateSource.take(5).runWith(Sink.seq).futureValue

        forAll(recs) {
          case csp: ClientSessionProtocol =>
            fail(s"Got an unexpected message type ${csp.getClass}")

          case ip: InternalSessionProtocol =>
            ip match {
              case UpdateSession(sid, s, _) =>
                expected.map(_.sessionId) must contain(sid)
                expected must contain(s)

              case RemoveSession(sid, _) =>
                sid mustBe session2.sessionId
            }
        }
      }

    }

  }
}
