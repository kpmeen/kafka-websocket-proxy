package net.scalytica.kafka.wsproxy.codecs

import net.scalytica.kafka.wsproxy.codecs.Encoders._
import net.scalytica.kafka.wsproxy.codecs.Decoders._
import net.scalytica.kafka.wsproxy.models.{WsClientId, WsGroupId, WsServerId}
import net.scalytica.kafka.wsproxy.session.{
  ClientInstance,
  ConsumerInstance,
  ConsumerSession,
  ProducerInstance,
  ProducerSession,
  Session,
  SessionId
}
import io.circe.syntax._
import org.scalatest.OptionValues
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec

class JsonCodecsSpec extends AnyWordSpec with Matchers with OptionValues {

  val sessionOne = SessionId("1")
  val groupOne   = WsGroupId("group1")
  val clientOne  = WsClientId("client1")
  val serverOne  = WsServerId("server1")

  val consumerInstanceOne: ClientInstance = ConsumerInstance(
    clientId = clientOne,
    groupId = groupOne,
    serverId = serverOne
  )

  val producerInstanceOne: ClientInstance = ProducerInstance(
    clientId = clientOne,
    serverId = serverOne
  )

  val emptyConsumerSession: Session = ConsumerSession(
    sessionId = sessionOne,
    groupId = groupOne,
    maxConnections = 2,
    instances = Set.empty
  )

  val consumerSession: Session = ConsumerSession(
    sessionId = sessionOne,
    groupId = groupOne,
    maxConnections = 2,
    instances = Set(consumerInstanceOne)
  )

  val emptyProducerSession: Session = ProducerSession(
    sessionId = sessionOne,
    maxConnections = 1,
    instances = Set.empty
  )

  val producerSession: Session = ProducerSession(
    sessionId = sessionOne,
    maxConnections = 1,
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
