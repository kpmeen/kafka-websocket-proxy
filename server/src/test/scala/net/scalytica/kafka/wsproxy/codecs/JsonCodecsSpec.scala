package net.scalytica.kafka.wsproxy.codecs

import net.scalytica.kafka.wsproxy.codecs.Encoders._
import net.scalytica.kafka.wsproxy.codecs.Decoders._
import net.scalytica.kafka.wsproxy.models._
import net.scalytica.kafka.wsproxy.session._
import io.circe.syntax._
import io.circe.parser._
import net.scalytica.kafka.wsproxy.config.Configuration.{
  ConsumerSpecificLimitCfg,
  DynamicCfg
}
import org.scalatest.{CustomEitherValues, OptionValues}
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec

// scalastyle:off magic.number
class JsonCodecsSpec
    extends AnyWordSpec
    with Matchers
    with OptionValues
    with CustomEitherValues {

  val sessionOne  = SessionId("session1")
  val groupOne    = WsGroupId("group1")
  val consumerOne = WsClientId("consumer1")
  val producerOne = WsProducerId("producer1")
  val instanceOne = WsProducerInstanceId("instance1")
  val serverOne   = WsServerId("server1")

  val consumerInstanceOne: ClientInstance = ConsumerInstance(
    id = FullConsumerId(groupOne, consumerOne),
    serverId = serverOne
  )

  val producerInstanceOne: ClientInstance = ProducerInstance(
    id = FullProducerId(producerOne, Option(instanceOne)),
    serverId = serverOne
  )

  val emptyConsumerSession: Session = ConsumerSession(
    sessionId = sessionOne,
    groupId = groupOne,
    instances = Set.empty
  )

  val consumerSession: Session = ConsumerSession(
    sessionId = sessionOne,
    groupId = groupOne,
    instances = Set(consumerInstanceOne)
  )

  val emptyProducerSession: Session = ProducerSession(
    sessionId = sessionOne,
    instances = Set.empty
  )

  val producerSession: Session = ProducerSession(
    sessionId = sessionOne,
    instances = Set(producerInstanceOne)
  )

  "Working with JSON" when {

    "using the client instance codecs" should {
      "encode and decode a consumer instance" in {
        val js = consumerInstanceOne.asJson

        js.as[ClientInstance] match {
          case Right(res) =>
            res mustBe a[ConsumerInstance]
            res mustBe consumerInstanceOne

          case Left(err) =>
            fail(s"Decoding failed with message: ${err.message}")
        }
      }

      "encode and decode a producer instance" in {
        val js = producerInstanceOne.asJson

        js.as[ClientInstance] match {
          case Right(res) =>
            res mustBe a[ProducerInstance]
            res mustBe producerInstanceOne

          case Left(err) =>
            fail(s"Decoding failed with message: ${err.message}")
        }
      }
    }

    "using the session codecs" should {

      "encode and decode a consumer session object with no instances" in {
        val js = emptyConsumerSession.asJson
        js.as[Session] match {
          case Right(res) =>
            res mustBe a[ConsumerSession]
            res mustBe emptyConsumerSession

          case Left(err) =>
            fail(s"Decoding failed with message: ${err.message}")
        }
      }

      "encode and decode a consumer session object with one instance" in {
        val js = consumerSession.asJson
        js.as[Session] match {
          case Right(res) =>
            res mustBe a[ConsumerSession]
            res mustBe consumerSession
            res.instances.size mustBe 1

          case Left(err) =>
            fail(s"Decoding failed with message: ${err.message}")
        }
      }

      "encode and decode a producer session object with no instances" in {
        val js = emptyProducerSession.asJson
        js.as[Session] match {
          case Right(res) =>
            res mustBe a[ProducerSession]
            res mustBe emptyProducerSession

          case Left(err) =>
            fail(s"Decoding failed with message: ${err.message}")
        }
      }

      "encode and decode a producer session object with one instance" in {
        val js = producerSession.asJson
        js.as[Session] match {
          case Right(res) =>
            res mustBe a[ProducerSession]
            res mustBe producerSession
            res.instances.size mustBe 1

          case Left(err) =>
            fail(s"Decoding failed with message: ${err.message}")
        }
      }

      "encode and decode a dynamic config object" in {
        val cslc: DynamicCfg = ConsumerSpecificLimitCfg(
          groupId = WsGroupId("group-1"),
          messagesPerSecond = Some(123),
          maxConnections = Some(1),
          batchSize = Some(1234)
        )
        val js = cslc.asJson

        js.as[DynamicCfg] match {
          case Right(res) =>
            res match {
              case cfg: ConsumerSpecificLimitCfg =>
                cfg.groupId.value mustBe "group-1"
                cfg.messagesPerSecond.value mustBe 123
                cfg.maxConnections.value mustBe 1
                cfg.batchSize.value mustBe 1234

              case wrong =>
                fail(
                  "Expected a ConsumerSpecificLimitCfg but" +
                    s" got ${wrong.niceClassSimpleName}"
                )
            }

          case Left(err) =>
            err.printStackTrace()
            fail(s"Decoding failed with message: ${err.message}")
        }
      }

      "fail when decoding an incorrect dynamic config object" in {
        val json = """{
                     |  "batch-size" : 1234,
                     |  "grop-id" : "group-1",
                     |  "max-connections" : 1,
                     |  "messages-per-second" : 123
                     |}""".stripMargin
        val js = parse(json).rightValue

        js.as[DynamicCfg] match {
          case Right(_) =>
            fail("Expected invalid json")

          case Left(err) =>
            err.message must include("Cannot convert configuration")
        }
      }

    }
  }

}
// scalastyle:on magic.number
