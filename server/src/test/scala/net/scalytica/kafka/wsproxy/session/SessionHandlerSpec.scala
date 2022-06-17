package net.scalytica.kafka.wsproxy.session

import akka.Done
import akka.actor.typed.{ActorRef, Scheduler}
import akka.actor.typed.scaladsl.adapter._
import akka.util.Timeout
import akka.testkit.TestDuration
import io.github.embeddedkafka._
import net.scalytica.kafka.wsproxy.codecs.{SessionIdSerde, SessionSerde}
import net.scalytica.kafka.wsproxy.config.Configuration.AppCfg
import net.scalytica.kafka.wsproxy.models._
import net.scalytica.kafka.wsproxy.session.SessionHandlerImplicits._
import net.scalytica.test.{TestDataGenerators, WsProxyKafkaSpec}
import org.apache.kafka.common.serialization.Deserializer
import org.scalatest.Inspectors.forAll
import org.scalatest._
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.matchers.must.Matchers
import org.scalatest.time.{Minute, Span}
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.duration._

// scalastyle:off magic.number
class SessionHandlerSpec
    extends AnyWordSpec
    with Matchers
    with BeforeAndAfter
    with Eventually
    with ScalaFutures
    with OptionValues
    with WsProxyKafkaSpec
    with TestDataGenerators
    with EmbeddedKafka {

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(1, Minute))

  implicit val timeout: Timeout     = 3.seconds.dilated
  implicit val scheduler: Scheduler = system.scheduler.toTyped

  implicit val keyDes: Deserializer[SessionId] =
    new SessionIdSerde().deserializer()

  implicit val valDes: Deserializer[Session] =
    new SessionSerde().deserializer()

  val testTopic = defaultTestAppCfg.sessionHandler.topicName

  case class Ctx(
      sh: ActorRef[SessionHandlerProtocol.Protocol],
      wsCfg: AppCfg,
      kcfg: EmbeddedKafkaConfig
  )

  def consumeSingleMessage()(
      implicit kcfg: EmbeddedKafkaConfig
  ): (SessionId, Session) =
    consumeFirstKeyedMessageFrom[SessionId, Session](
      topic = testTopic.value,
      timeout = 5.seconds.dilated
    )

  def sessionHandlerCtx[T](body: Ctx => T): Assertion =
    withRunningKafkaOnFoundPort(embeddedKafkaConfig) { implicit kcfg =>
      implicit val wsCfg = plainAppTestConfig(kcfg.kafkaPort)

      val shr  = SessionHandler.init
      val ctrl = shr.stream.run()

      body(Ctx(shr.shRef, wsCfg, kcfg))

      shr.shRef.tell(
        SessionHandlerProtocol.StopSessionHandler(system.toTyped.ignoreRef)
      )

      ctrl.shutdown().futureValue mustBe Done
    }

  def validateSession(actual: Session)(
      expectedSessionId: SessionId,
      expectedMaxConnections: Int,
      expectedNumClients: Int = 0
  ): Assertion = {
    actual.sessionId mustBe expectedSessionId
    actual.maxConnections mustBe expectedMaxConnections
    actual.instances.size mustBe expectedNumClients
  }

  def validateConsumer(actual: ConsumerInstance)(
      expectedClientId: WsClientId,
      expectedServerId: WsServerId
  ): Assertion = {
    actual.id.clientId mustBe expectedClientId
    actual.serverId mustBe expectedServerId
  }

  def initAndValidateConsumerSession(
      groupId: WsGroupId,
      consumerLimit: Int
  )(implicit ctx: Ctx): SessionOpResult = {
    val res = ctx.sh.initConsumerSession(groupId, consumerLimit).futureValue
    res mustBe a[SessionInitialised]
    validateSession(res.session)(SessionId(groupId), consumerLimit)
    res
  }

  "The SessionHandler" when {

    "working with consumer sessions" should {

      "register a new consumer session" in sessionHandlerCtx { implicit ctx =>
        implicit val kcfg = ctx.kcfg

        val grpId = WsGroupId("group1")
        val sid   = SessionId(grpId)

        val res = ctx.sh.initConsumerSession(grpId, 3).futureValue
        validateSession(res.session)(sid, 3)

        val (k, v) = consumeSingleMessage()

        k mustBe sid
        v mustBe ConsumerSession(sid, grpId, maxConnections = 3)
      }

      "add a few new consumer sessions" in sessionHandlerCtx { implicit ctx =>
        implicit val kcfg = ctx.kcfg

        val grp1 = WsGroupId("group1")
        val grp2 = WsGroupId("group2")
        val grp3 = WsGroupId("group3")
        val sid1 = SessionId(grp1)
        val sid2 = SessionId(grp2)
        val sid3 = SessionId(grp3)

        val r1 = ctx.sh.initConsumerSession(grp1, 3).futureValue
        val r2 = ctx.sh.initConsumerSession(grp2, 2).futureValue
        val r3 = ctx.sh.initConsumerSession(grp3, 1).futureValue

        validateSession(r1.session)(sid1, 3)
        validateSession(r2.session)(sid2, 2)
        validateSession(r3.session)(sid3, 1)

        val kvs =
          consumeNumberKeyedMessagesFrom[SessionId, Session](
            topic = testTopic.value,
            number = 3
          )

        forAll(kvs.zipWithIndex) { case ((k, v), idx) =>
          k mustBe SessionId(s"group${idx + 1}")
          v.sessionId mustBe SessionId(s"group${idx + 1}")
          v.instances mustBe empty
        }
      }

      "add consumer to a consumer session" in
        sessionHandlerCtx { implicit ctx =>
          implicit val kcfg = ctx.kcfg

          val grpId = WsGroupId("group1")

          val s = ctx.sh.initConsumerSession(grpId, 2).futureValue.session
          validateSession(s)(SessionId(grpId), 2)
          validateSession(consumeSingleMessage()._2)(SessionId(grpId), 2)

          val r2 = ctx.sh
            .addConsumer(
              FullConsumerId(grpId, WsClientId("client1")),
              WsServerId("n1")
            )
            .futureValue
          validateSession(consumeSingleMessage()._2)(
            expectedSessionId = s.sessionId,
            expectedMaxConnections = s.maxConnections,
            expectedNumClients = 1
          )
          validateSession(r2.session)(s.sessionId, s.maxConnections, 1)
          r2.session.canOpenSocket mustBe true
          r2.session.instances.headOption.value mustBe a[ConsumerInstance]
          val ci =
            r2.session.instances.headOption.value.asInstanceOf[ConsumerInstance]
          validateConsumer(ci)(WsClientId("client1"), WsServerId("n1"))
        }

      "not allow adding a consumer if the session has reached its limit" in
        sessionHandlerCtx { implicit ctx =>
          implicit val kcfg = ctx.kcfg
          val grpId         = WsGroupId("group1")
          val sid           = SessionId(grpId)
          val s =
            ctx.sh.initConsumerSession(grpId, 2).futureValue.session
          validateSession(s)(sid, 2)
          validateSession(consumeSingleMessage()._2)(
            expectedSessionId = SessionId("group1"),
            expectedMaxConnections = 2
          )

          val r2 = ctx.sh
            .addConsumer(
              FullConsumerId(grpId, WsClientId("client1")),
              WsServerId("n1")
            )
            .futureValue
          validateSession(r2.session)(s.sessionId, s.maxConnections, 1)
          r2.session.canOpenSocket mustBe true
          r2.session.instances.headOption.value mustBe a[ConsumerInstance]
          val ci1 =
            r2.session.instances.headOption.value.asInstanceOf[ConsumerInstance]
          validateConsumer(ci1)(WsClientId("client1"), WsServerId("n1"))
          validateSession(consumeSingleMessage()._2)(
            expectedSessionId = s.sessionId,
            expectedMaxConnections = s.maxConnections,
            expectedNumClients = 1
          )

          val r3 = ctx.sh
            .addConsumer(
              FullConsumerId(grpId, WsClientId("client2")),
              WsServerId("n2")
            )
            .futureValue
          r3.session.canOpenSocket mustBe false
          r3.session.instances.lastOption.value mustBe a[ConsumerInstance]
          val ci2 =
            r3.session.instances.lastOption.value.asInstanceOf[ConsumerInstance]
          validateConsumer(ci2)(WsClientId("client2"), WsServerId("n2"))
          validateSession(consumeSingleMessage()._2)(
            expectedSessionId = s.sessionId,
            expectedMaxConnections = s.maxConnections,
            expectedNumClients = 2
          )

          val r4 = ctx.sh
            .addConsumer(
              FullConsumerId(grpId, WsClientId("client3")),
              WsServerId("n1")
            )
            .futureValue
          r4 mustBe a[InstanceLimitReached]
          r4.session mustBe r3.session
        }

    }

    "working with producer sessions" should {

      "register a new producer session" in sessionHandlerCtx { implicit ctx =>
        implicit val kcfg = ctx.kcfg

        val cid = WsProducerId("clientId1")
        val sid = SessionId(cid)

        val res = ctx.sh.initProducerSession(cid, 3).futureValue
        validateSession(res.session)(sid, 3)

        val (k, v) = consumeSingleMessage()

        k mustBe sid
        v mustBe ProducerSession(sid, maxConnections = 3)
      }
    }
  }
}
