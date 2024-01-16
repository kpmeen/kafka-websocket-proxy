package net.scalytica.kafka.wsproxy.config

import org.apache.pekko.Done
import org.apache.pekko.actor.typed.Scheduler
import org.apache.pekko.actor.typed.scaladsl.adapter._
import org.apache.pekko.util.Timeout
import org.apache.pekko.testkit.TestDuration
import net.scalytica.kafka.wsproxy.codecs.BasicSerdes.StringDeserializer
import net.scalytica.kafka.wsproxy.codecs.DynamicCfgSerde
import io.github.embeddedkafka.{EmbeddedKafka, EmbeddedKafkaConfig}
import net.scalytica.kafka.wsproxy.config.DynamicConfigHandlerImplicits._
import net.scalytica.kafka.wsproxy.config.Configuration.{
  AppCfg,
  DynamicCfg,
  ProducerSpecificLimitCfg
}
import net.scalytica.kafka.wsproxy.config.DynamicConfigHandlerProtocol._
import net.scalytica.test.WsProxyKafkaSpec
import org.apache.kafka.common.serialization.Deserializer
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.Inspectors.forAll
import org.scalatest.matchers.must.Matchers
import org.scalatest.time.{Minute, Span}
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{Assertion, BeforeAndAfter, OptionValues}

import scala.concurrent.duration._

// scalastyle:off magic.number
class DynamicConfigHandlerSpec
    extends AnyWordSpec
    with Matchers
    with BeforeAndAfter
    with Eventually
    with ScalaFutures
    with OptionValues
    with WsProxyKafkaSpec
    with DynamicConfigTestDataGenerators
    with EmbeddedKafka {
  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(1, Minute))

  implicit val timeout: Timeout     = 3.seconds.dilated
  implicit val scheduler: Scheduler = system.scheduler.toTyped

  implicit val valDes: Deserializer[DynamicCfg] =
    new DynamicCfgSerde().deserializer()

  val testTopic = defaultTestAppCfg.dynamicConfigHandler.topicName

  val tombstoneValue: DynamicCfg = null // scalastyle:ignore

  case class Ctx(
      ref: RunnableDynamicConfigHandlerRef,
      wsCfg: AppCfg,
      kcfg: EmbeddedKafkaConfig
  )

  def consumeSingleMessage()(
      implicit kcfg: EmbeddedKafkaConfig
  ): Option[(String, DynamicCfg)] =
    consumeNumberKeyedMessagesFrom[String, DynamicCfg](
      topic = testTopic.value,
      number = 1,
      autoCommit = true
    ).headOption

  def dynamicConfigHandlerCtx[T](body: Ctx => T): Assertion =
    withRunningKafkaOnFoundPort(embeddedKafkaConfig) { implicit kcfg =>
      implicit val wsCfg = plainAppTestConfig(
        kafkaPort = kcfg.kafkaPort,
        useDynamicConfigs = true
      )

      val dch  = DynamicConfigHandler.init
      val ctrl = dch.stream.run()

      body(Ctx(dch, wsCfg, kcfg))

      dch.dynamicConfigHandlerStop()

      ctrl.shutdown().futureValue mustBe Done
    }

  def addAndAssert(cfgs: DynamicCfg*)(implicit ctx: Ctx): Assertion = {
    forAll(cfgs) { cfg =>
      val r = ctx.ref.addConfig(cfg).futureValue
      r mustBe ConfigSaved(cfg)
    }
  }

  def addAndAssertTopic(cfg: DynamicCfg)(implicit ctx: Ctx): Assertion = {
    implicit val kcfg = ctx.kcfg

    addAndAssert(cfg)

    val (k, v) =
      consumeFirstKeyedMessageFrom[String, DynamicCfg](testTopic.value)

    k mustBe dynamicCfgTopicKey(cfg).value
    v mustBe cfg
  }

  def consumeAndAssert(
      cfgs: (String, DynamicCfg)*
  )(implicit ctx: Ctx): Assertion = {
    implicit val kcfg = ctx.kcfg

    val consumed =
      consumeNumberKeyedMessagesFrom[String, DynamicCfg](
        topic = testTopic.value,
        number = cfgs.size,
        timeout = 5.seconds.dilated
      )

    consumed must contain allElementsOf cfgs.toSeq
  }

  def assertGetAll(
      expectedCfgs: Map[String, DynamicCfg]
  )(implicit ctx: Ctx): Assertion = {
    eventually {
      ctx.ref.getAllConfigs().futureValue match {
        case FoundActiveConfigs(dc) =>
          dc.configs must contain allElementsOf expectedCfgs

        case bad =>
          fail(s"Got $bad, but exepected a ${classOf[FoundActiveConfigs]}")
      }
    }
  }

  "The DynamicConfigHandler" should {

    "add a new dynamic configuration" in
      dynamicConfigHandlerCtx { implicit ctx =>
        addAndAssertTopic(cfg1)
      }

    "update an existing dynamic configuration" in
      dynamicConfigHandlerCtx { implicit ctx =>
        val add = cfg1.asInstanceOf[ProducerSpecificLimitCfg]
        val upd = add.copy(messagesPerSecond = Some(50))

        addAndAssertTopic(add)
        addAndAssertTopic(upd)
      }

    "remove an existing dynamic configuration" in
      dynamicConfigHandlerCtx { implicit ctx =>
        val (remKey, _) = expectedSeq(3)
        // Add a tombstone to the expected values
        val remRec = remKey -> tombstoneValue

        addAndAssert(expectedValues: _*)
        consumeAndAssert(expectedSeq: _*)

        val r1 = ctx.ref.removeConfig(remKey).futureValue
        r1 mustBe ConfigRemoved(remKey)

        consumeAndAssert(remRec)
      }

    "remove all existing dynamic configurations" in
      dynamicConfigHandlerCtx { implicit ctx =>
        val removed = expectedKeys.map(k => k -> tombstoneValue)

        addAndAssert(expectedValues: _*)
        consumeAndAssert(expectedSeq: _*)
        assertGetAll(expectedMap)

        val r1 = ctx.ref.removeAllConfigs().futureValue
        r1 mustBe RemovedAllConfigs()

        consumeAndAssert(removed: _*)
        assertGetAll(Map.empty)
      }

    "get all active dynamic configurations" in
      dynamicConfigHandlerCtx { implicit ctx =>
        addAndAssert(expectedValues: _*)
        assertGetAll(expectedMap)
      }
  }
}
