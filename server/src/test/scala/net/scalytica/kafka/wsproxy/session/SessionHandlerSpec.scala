package net.scalytica.kafka.wsproxy.session

import akka.Done
import akka.actor.Scheduler
import akka.actor.typed.ActorRef
import akka.util.Timeout
import net.manub.embeddedkafka.schemaregistry._
import net.scalytica.kafka.wsproxy.Configuration.AppCfg
import net.scalytica.kafka.wsproxy.codecs.Decoders.sessionDecoder
import net.scalytica.kafka.wsproxy.codecs.Encoders.sessionEncoder
import net.scalytica.kafka.wsproxy.codecs.{SessionSerde, WsGroupIdSerde}
import net.scalytica.kafka.wsproxy.models.{WsClientId, WsGroupId, WsServerId}
import net.scalytica.kafka.wsproxy.session.Session.SessionOpResult
import net.scalytica.kafka.wsproxy.session.SessionHandler._
import net.scalytica.test.{TestDataGenerators, WSProxyKafkaSpec}
import org.apache.kafka.common.serialization.Deserializer
import org.scalatest.Inspectors.forAll
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.time.{Minute, Span}
import org.scalatest._

import scala.concurrent.duration._

// scalastyle:off magic.number
class SessionHandlerSpec
    extends WordSpec
    with MustMatchers
    with BeforeAndAfter
    with Eventually
    with ScalaFutures
    with OptionValues
    with WSProxyKafkaSpec
    with TestDataGenerators
    with EmbeddedKafka {

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(1, Minute))

  implicit val timeout: Timeout     = 3 seconds
  implicit val scheduler: Scheduler = system.scheduler

  implicit val keyDes: Deserializer[WsGroupId] =
    new WsGroupIdSerde().deserializer()
  implicit val valDes: Deserializer[Session] = new SessionSerde().deserializer()

  val testTopic = defaultTestAppCfg.sessionHandler.sessionStateTopicName

  case class Ctx(
      sh: ActorRef[SessionHandlerProtocol.Protocol],
      wsCfg: AppCfg,
      kcfg: EmbeddedKafkaConfig
  )

  def consumeSingleMessage()(
      implicit kcfg: EmbeddedKafkaConfig
  ): Option[(WsGroupId, Session)] =
    consumeNumberKeyedMessagesFrom[WsGroupId, Session](
      topic = testTopic.value,
      number = 1,
      autoCommit = true
    ).headOption

  def sessionHandlerCtx[T](serverId: String)(body: Ctx => T): Assertion =
    withRunningKafkaOnFoundPort(embeddedKafkaConfig) { implicit kcfg =>
      implicit val wsCfg =
        appTestConfig(kafkaPort = kcfg.kafkaPort, serverId = serverId)

      val (sdcStream, shRef) = SessionHandler.init
      val ctrl               = sdcStream.run()

      body(Ctx(shRef, wsCfg, kcfg))

      ctrl.shutdown().futureValue mustBe Done
    }

  def validateSession(actual: Session)(
      expectedGroupId: WsGroupId,
      expectedConsumerLimit: Int,
      expectedNumConsumers: Int = 0
  ): Assertion = {
    actual.consumerGroupId mustBe expectedGroupId
    actual.consumerLimit mustBe expectedConsumerLimit
    actual.consumers.size mustBe expectedNumConsumers
  }

  def validateConsumer(actual: ConsumerInstance)(
      expectedConsumerId: WsClientId,
      expectedServerId: WsServerId
  ): Assertion = {
    actual.id mustBe expectedConsumerId
    actual.serverId mustBe expectedServerId
  }

  def initAndValidateSession(
      groupId: WsGroupId,
      consumerLimit: Int
  )(implicit ctx: Ctx): SessionOpResult = {
    val res = ctx.sh.initSession(groupId, consumerLimit).futureValue
    res mustBe a[Session.SessionInitialised]
    validateSession(res.session)(groupId, consumerLimit)
    res
  }

  "The SessionHandler" should {

    "register a new session" in sessionHandlerCtx("n1") { implicit ctx =>
      implicit val kcfg = ctx.kcfg

      val res = ctx.sh.initSession(WsGroupId("group1"), 3).futureValue
      validateSession(res.session)(WsGroupId("group1"), 3)

      val (k, v) =
        consumeFirstKeyedMessageFrom[WsGroupId, Session](testTopic.value)

      k mustBe WsGroupId("group1")
      v mustBe Session(WsGroupId("group1"), consumerLimit = 3)
    }

    "add a few new sessions" in sessionHandlerCtx("n2") { implicit ctx =>
      implicit val kcfg = ctx.kcfg

      val r1 = ctx.sh.initSession(WsGroupId("group1"), 3).futureValue
      val r2 = ctx.sh.initSession(WsGroupId("group2"), 2).futureValue
      val r3 = ctx.sh.initSession(WsGroupId("group3"), 1).futureValue

      validateSession(r1.session)(WsGroupId("group1"), 3)
      validateSession(r2.session)(WsGroupId("group2"), 2)
      validateSession(r3.session)(WsGroupId("group3"), 1)

      val kvs =
        consumeNumberKeyedMessagesFrom[WsGroupId, Session](testTopic.value, 3)

      forAll(kvs.zipWithIndex) {
        case ((k, v), idx) =>
          k mustBe WsGroupId(s"group${idx + 1}")
          v.consumerGroupId mustBe WsGroupId(s"group${idx + 1}")
          v.consumers mustBe empty
      }
    }

    "add a consumer to a session" in sessionHandlerCtx("n3") { implicit ctx =>
      implicit val kcfg = ctx.kcfg

      val s = ctx.sh.initSession(WsGroupId("group1"), 2).futureValue.session
      validateSession(s)(WsGroupId("group1"), 2)

      validateSession(consumeSingleMessage().value._2)(WsGroupId("group1"), 2)

      val r2 = ctx.sh
        .addConsumer(
          s.consumerGroupId,
          WsClientId("client1"),
          WsServerId("n1")
        )
        .futureValue
      validateSession(consumeSingleMessage().value._2)(
        expectedGroupId = s.consumerGroupId,
        expectedConsumerLimit = s.consumerLimit,
        expectedNumConsumers = 1
      )
      validateSession(r2.session)(s.consumerGroupId, s.consumerLimit, 1)
      r2.session.canOpenSocket mustBe true
      validateConsumer(r2.session.consumers.headOption.value)(
        WsClientId("client1"),
        WsServerId("n1")
      )
    }

    "not allow adding a consumer if the session has reached its limit" in
      sessionHandlerCtx("n4") { implicit ctx =>
        implicit val kcfg = ctx.kcfg

        val s = ctx.sh.initSession(WsGroupId("group1"), 2).futureValue.session
        validateSession(s)(WsGroupId("group1"), 2)
        validateSession(consumeSingleMessage().value._2)(
          expectedGroupId = WsGroupId("group1"),
          expectedConsumerLimit = 2
        )

        val r2 = ctx.sh
          .addConsumer(
            s.consumerGroupId,
            WsClientId("client1"),
            WsServerId("n1")
          )
          .futureValue
        validateSession(r2.session)(s.consumerGroupId, s.consumerLimit, 1)
        r2.session.canOpenSocket mustBe true
        validateConsumer(r2.session.consumers.headOption.value)(
          WsClientId("client1"),
          WsServerId("n1")
        )
        validateSession(consumeSingleMessage().value._2)(
          expectedGroupId = s.consumerGroupId,
          expectedConsumerLimit = s.consumerLimit,
          expectedNumConsumers = 1
        )

        val r3 = ctx.sh
          .addConsumer(
            s.consumerGroupId,
            WsClientId("client2"),
            WsServerId("n2")
          )
          .futureValue
        r3.session.canOpenSocket mustBe false
        validateConsumer(r3.session.consumers.lastOption.value)(
          WsClientId("client2"),
          WsServerId("n2")
        )
        validateSession(consumeSingleMessage().value._2)(
          expectedGroupId = s.consumerGroupId,
          expectedConsumerLimit = s.consumerLimit,
          expectedNumConsumers = 2
        )

        val r4 = ctx.sh
          .addConsumer(
            s.consumerGroupId,
            WsClientId("client3"),
            WsServerId("n1")
          )
          .futureValue
        r4 mustBe a[Session.ConsumerLimitReached]
        r4.session mustBe r3.session
      }

  }
}
