package net.scalytica.kafka.wsproxy.codecs

import net.scalytica.kafka.wsproxy.codecs.Encoders._
import net.scalytica.kafka.wsproxy.codecs.Decoders._
import net.scalytica.kafka.wsproxy.models._
import net.scalytica.kafka.wsproxy.session._
import io.circe.syntax._
import org.scalatest.OptionValues
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec

class JsonCodecsSpec extends AnyWordSpec with Matchers with OptionValues {

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

    }
  }

}
