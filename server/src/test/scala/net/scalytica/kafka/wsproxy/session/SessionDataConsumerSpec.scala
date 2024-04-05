package net.scalytica.kafka.wsproxy.session

import io.github.embeddedkafka.{EmbeddedKafka, EmbeddedKafkaConfig}
import net.scalytica.kafka.wsproxy.codecs.{SessionIdSerde, SessionSerde}
import net.scalytica.kafka.wsproxy.config.Configuration.AppCfg
import net.scalytica.kafka.wsproxy.models.{TopicName, WsGroupId}
import net.scalytica.kafka.wsproxy.session.SessionHandlerProtocol.{
  ClientSessionProtocol,
  InternalSessionProtocol,
  RemoveSession,
  UpdateSession
}
import net.scalytica.test.SharedAttributes.defaultTypesafeConfig
import net.scalytica.test.{WsProxySpec, WsReusableProxyKafkaFixture}
import org.apache.kafka.common.serialization.Serializer
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.stream.scaladsl.Sink
import org.scalatest.Inspectors.forAll
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.matchers.must.Matchers
import org.scalatest.time.{Minute, Span}
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{BeforeAndAfterAll, OptionValues}

// scalastyle:off magic.number
class SessionDataConsumerSpec
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
    "sessionhandler-consumer-test-topic"

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(1, Minute))

  private[this] val atk =
    ActorTestKit("session-data-consumer-test", defaultTypesafeConfig)

  implicit private[this] val sys: ActorSystem[_] = atk.system

  implicit private[this] val sessionIdSerde: Serializer[SessionId] =
    new SessionIdSerde()
  implicit private[this] val sessionSerde: Serializer[Session] =
    new SessionSerde()

  override def afterAll(): Unit = {
    materializer.shutdown()
    atk.shutdownTestKit()
    super.afterAll()
  }

  private[this] def publish(
      sessionTopic: TopicName,
      session: Session
  )(implicit config: EmbeddedKafkaConfig): Unit = {
    publishToKafka[SessionId, Session](
      topic = sessionTopic.value,
      key = session.sessionId,
      message = session
    )
  }

  private[this] def publishTombstone(
      sessionTopic: TopicName,
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

  private[this] val session1 = testConsumerSession(1)
  private[this] val session2 = testConsumerSession(2)
  private[this] val session3 = testConsumerSession(3)
  private[this] val session4 = testConsumerSession(4)

  "The SessionConsumer" should {

    "consume session data from the session state topic" in {
      withNoContext(useFreshStateTopics = true) { case (kcfg, appCfg) =>
        implicit val kafkaCfg: EmbeddedKafkaConfig = kcfg
        implicit val cfg: AppCfg                   = appCfg

        kafkaContext.createTopics(Map(cfg.sessionHandler.topicName.value -> 1))

        val expected = List(session1, session2, session3, session4)

        // Prepare the topic with some messages
        expected.foreach(s => publish(cfg.sessionHandler.topicName, s))
        // Publish tombstone for session2
        publishTombstone(cfg.sessionHandler.topicName, session2.sessionId)

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
