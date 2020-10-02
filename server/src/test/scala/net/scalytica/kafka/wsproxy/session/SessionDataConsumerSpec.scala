package net.scalytica.kafka.wsproxy.session

import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.stream.scaladsl.Sink
import net.manub.embeddedkafka.schemaregistry.{
  EmbeddedKafka,
  EmbeddedKafkaConfig
}
import net.scalytica.kafka.wsproxy.codecs.Implicits._
import net.scalytica.kafka.wsproxy.codecs.{
  BasicSerdes,
  SessionSerde,
  WsGroupIdSerde
}
import net.scalytica.kafka.wsproxy.models.WsGroupId
import net.scalytica.kafka.wsproxy.session.SessionHandlerProtocol.{
  ClientSessionProtocol,
  InternalProtocol,
  RemoveSession,
  UpdateSession
}
import net.scalytica.test.WsProxyKafkaSpec
import org.scalatest.Inspectors.forAll
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.time.{Minute, Span}
import org.scalatest.{BeforeAndAfterAll, OptionValues}
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec

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

  val sessionTopic = testCfg.sessionHandler.sessionStateTopicName

  val atk = ActorTestKit("session-data-consumer-test", config)

  implicit val sys = atk.system

  implicit val groupIdSerde = new WsGroupIdSerde()
  implicit val sessionSerde = new SessionSerde()
  implicit val keyDes       = BasicSerdes.StringDeserializer
  implicit val keySer       = BasicSerdes.StringSerializer

  override def afterAll(): Unit = {
    materializer.shutdown()
    atk.shutdownTestKit()
    super.afterAll()
  }

  private[this] def publish(
      s: Session
  )(implicit config: EmbeddedKafkaConfig): Unit = {
    publishToKafka[WsGroupId, Session](
      topic = sessionTopic.value,
      key = s.consumerGroupId,
      message = s
    )
  }

  private[this] def publishTombstone(
      gid: WsGroupId
  )(implicit config: EmbeddedKafkaConfig): Unit = {
    publishToKafka[WsGroupId, Session](
      topic = sessionTopic.value,
      key = gid,
      message = null // scalastyle:ignore
    )
  }

  private[this] def testSession(i: Int): Session =
    Session(WsGroupId(s"c$i"), consumerLimit = i)

  val session1 = testSession(1)
  val session2 = testSession(2)
  val session3 = testSession(3)
  val session4 = testSession(4)

  "The SessionConsumer" should {

    "consume session data from the session state topic" in {
      withRunningKafkaOnFoundPort(embeddedKafkaConfig) { implicit kcfg =>
        implicit val cfg =
          plainAppTestConfig(kcfg.kafkaPort, Option(kcfg.schemaRegistryPort))

        initTopic(cfg.sessionHandler.sessionStateTopicName.value)

        val expected = List(session1, session2, session3, session4)

        // Prepare the topic with some messages
        expected.foreach(publish)
        // Publish tombstone for session2
        publishTombstone(session2.consumerGroupId)

        val sdc  = new SessionDataConsumer()
        val recs = sdc.sessionStateSource.take(5).runWith(Sink.seq).futureValue

        forAll(recs) {
          case csp: ClientSessionProtocol =>
            fail(s"Got an unexpected message type ${csp.getClass}")

          case ip: InternalProtocol =>
            ip match {
              case UpdateSession(gid, s) =>
                expected.map(_.consumerGroupId) must contain(gid)
                expected must contain(s)

              case RemoveSession(gid) =>
                gid mustBe session2.consumerGroupId
            }
        }
      }

    }

  }
}
