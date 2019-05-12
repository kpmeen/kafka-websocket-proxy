package net.scalytica.kafka.wsproxy.session

import akka.Done
import akka.actor.Scheduler
import akka.actor.typed.ActorRef
import akka.util.Timeout
import net.scalytica.kafka.wsproxy.session.SessionHandler._
import net.manub.embeddedkafka.schemaregistry._
import net.scalytica.kafka.wsproxy.Configuration.AppCfg
import net.scalytica.kafka.wsproxy.codecs.SessionSerde
import net.scalytica.kafka.wsproxy.codecs.Decoders.sessionDecoder
import net.scalytica.kafka.wsproxy.codecs.Encoders.sessionEncoder
import net.scalytica.kafka.wsproxy.session.Session.SessionOpResult
import net.scalytica.test.{TestDataGenerators, WSProxyKafkaSpec}
import org.apache.kafka.common.serialization.{Deserializer, Serdes => KSerdes}
import org.scalatest.{
  Assertion,
  BeforeAndAfter,
  MustMatchers,
  OptionValues,
  WordSpec
}
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.Inspectors.forAll
import org.scalatest.time.{Minute, Span}

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

  implicit val keyDes: Deserializer[String]  = KSerdes.String().deserializer()
  implicit val valDes: Deserializer[Session] = new SessionSerde().deserializer()

  val testTopic = defaultTestAppCfg.sessionHandler.sessionStateTopicName

  case class Ctx(
      sh: ActorRef[SessionHandlerProtocol.Protocol],
      wsCfg: AppCfg,
      kcfg: EmbeddedKafkaConfig
  )

  def consumeSingleMessage()(
      implicit kcfg: EmbeddedKafkaConfig
  ): Option[(String, Session)] =
    consumeNumberKeyedMessagesFrom[String, Session](
      topic = testTopic,
      number = 1,
      autoCommit = true
    ).headOption

  def sessionHandlerCtx[T](serverId: Int)(body: Ctx => T): Assertion =
    withRunningKafkaOnFoundPort(embeddedKafkaConfig) { implicit kcfg =>
      implicit val wsCfg =
        appTestConfig(kafkaPort = kcfg.kafkaPort, serverId = serverId)

      val (sdcStream, shRef) = SessionHandler.init
      val ctrl               = sdcStream.run()

      body(Ctx(shRef, wsCfg, kcfg))

      ctrl.shutdown().futureValue mustBe Done
    }

  def validateSession(actual: Session)(
      expectedGroupId: String,
      expectedConsumerLimit: Int,
      expectedNumConsumers: Int = 0
  ): Assertion = {
    actual.consumerGroupId mustBe expectedGroupId
    actual.consumerLimit mustBe expectedConsumerLimit
    actual.consumers.size mustBe expectedNumConsumers
  }

  def validateConsumer(actual: ConsumerInstance)(
      expectedConsumerId: String,
      expectedServerId: Int
  ): Assertion = {
    actual.id mustBe expectedConsumerId
    actual.serverId mustBe expectedServerId
  }

  def initAndValidateSession(
      groupId: String,
      consumerLimit: Int
  )(implicit ctx: Ctx): SessionOpResult = {
    val res = ctx.sh.initSession(groupId, consumerLimit).futureValue
    res mustBe a[Session.SessionInitialised]
    validateSession(res.session)(groupId, consumerLimit)
    res
  }

  "The SessionHandler" should {

    "register a new session" in sessionHandlerCtx(1) { implicit ctx =>
      implicit val kcfg = ctx.kcfg

      val res = ctx.sh.initSession("group1", 3).futureValue
      validateSession(res.session)("group1", 3)

      val (k, v) = consumeFirstKeyedMessageFrom[String, Session](testTopic)

      k mustBe "group1"
      v mustBe Session("group1", consumerLimit = 3)
    }

    "add a few new sessions" in sessionHandlerCtx(2) { implicit ctx =>
      implicit val kcfg = ctx.kcfg

      val r1 = ctx.sh.initSession("group1", 3).futureValue
      val r2 = ctx.sh.initSession("group2", 2).futureValue
      val r3 = ctx.sh.initSession("group3", 1).futureValue

      validateSession(r1.session)("group1", 3)
      validateSession(r2.session)("group2", 2)
      validateSession(r3.session)("group3", 1)

      val kvs = consumeNumberKeyedMessagesFrom[String, Session](testTopic, 3)

      forAll(kvs.zipWithIndex) {
        case ((k, v), idx) =>
          k mustBe s"group${idx + 1}"
          v.consumerGroupId mustBe s"group${idx + 1}"
          v.consumers mustBe empty
      }
    }

    "add a consumer to a session" in sessionHandlerCtx(3) { implicit ctx =>
      implicit val kcfg = ctx.kcfg

      val s = ctx.sh.initSession("group1", 2).futureValue.session
      validateSession(s)("group1", 2)

      validateSession(consumeSingleMessage().value._2)("group1", 2)

      val r2 = ctx.sh.addConsumer(s.consumerGroupId, "client1", 1).futureValue
      validateSession(consumeSingleMessage().value._2)(
        expectedGroupId = s.consumerGroupId,
        expectedConsumerLimit = s.consumerLimit,
        expectedNumConsumers = 1
      )
      validateSession(r2.session)(s.consumerGroupId, s.consumerLimit, 1)
      r2.session.canOpenSocket mustBe true
      validateConsumer(r2.session.consumers.headOption.value)("client1", 1)
    }

    "not allow adding a consumer if the session has reached its limit" in
      sessionHandlerCtx(4) { implicit ctx =>
        implicit val kcfg = ctx.kcfg

        val s = ctx.sh.initSession("group1", 2).futureValue.session
        validateSession(s)("group1", 2)
        validateSession(consumeSingleMessage().value._2)(
          expectedGroupId = "group1",
          expectedConsumerLimit = 2
        )

        val r2 = ctx.sh.addConsumer(s.consumerGroupId, "client1", 1).futureValue
        validateSession(r2.session)(s.consumerGroupId, s.consumerLimit, 1)
        r2.session.canOpenSocket mustBe true
        validateConsumer(r2.session.consumers.headOption.value)("client1", 1)
        validateSession(consumeSingleMessage().value._2)(
          expectedGroupId = s.consumerGroupId,
          expectedConsumerLimit = s.consumerLimit,
          expectedNumConsumers = 1
        )

        val r3 = ctx.sh.addConsumer(s.consumerGroupId, "client2", 2).futureValue
        r3.session.canOpenSocket mustBe false
        validateConsumer(r3.session.consumers.lastOption.value)("client2", 2)
        validateSession(consumeSingleMessage().value._2)(
          expectedGroupId = s.consumerGroupId,
          expectedConsumerLimit = s.consumerLimit,
          expectedNumConsumers = 2
        )

        val r4 = ctx.sh.addConsumer(s.consumerGroupId, "client3", 1).futureValue
        r4 mustBe a[Session.ConsumerLimitReached]
        r4.session mustBe r3.session
      }

  }
}
