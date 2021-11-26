package net.scalytica.kafka.wsproxy.codecs

import net.scalytica.kafka.wsproxy.models.{WsClientId, WsGroupId, WsServerId}
import net.scalytica.kafka.wsproxy.session.{
  ConsumerInstance,
  ConsumerSession,
  ProducerInstance,
  ProducerSession,
  SessionId
}
import org.scalatest.OptionValues
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec

class SessionSerdeSpec extends AnyWordSpec with Matchers with OptionValues {

  private[this] val ConsumerSessionClsName =
    classOf[ConsumerSession].getSimpleName

  private[this] val ProducerSessionClsName =
    classOf[ProducerSession].getSimpleName

  val sessionSerde = new SessionSerde

  val emptyConsumerSession = ConsumerSession(
    sessionId = SessionId("empty-consumer-session"),
    groupId = WsGroupId("group-1"),
    maxConnections = 3,
    instances = Set.empty
  )

  val nonEmptyConsumerSession = ConsumerSession(
    sessionId = SessionId("non-empty-consumer-session"),
    groupId = WsGroupId("group-1"),
    maxConnections = 3,
    instances = Set(
      ConsumerInstance(
        clientId = WsClientId("consumer-1"),
        groupId = WsGroupId("group-1"),
        serverId = WsServerId("server-1")
      )
    )
  )

  val emptyProducerSession = ProducerSession(
    sessionId = SessionId("empty-producer-session"),
    instances = Set.empty
  )

  val nonEmptyProducerSession = ProducerSession(
    sessionId = SessionId("non-empty-producer-session"),
    instances = Set(
      ProducerInstance(
        clientId = WsClientId("producer-1"),
        serverId = WsServerId("server-1")
      )
    )
  )

  s"The ${classOf[SessionSerde].getName}" should {

    s"convert a $ConsumerSessionClsName with no instances to bytes" in {
      val bytes = sessionSerde.serialize("", emptyConsumerSession)
      bytes must not be empty
    }

    s"convert bytes to a $ConsumerSessionClsName with no instances" in {
      val bytes  = sessionSerde.serialize("", emptyConsumerSession)
      val result = sessionSerde.deserialize("", bytes)

      result mustBe a[ConsumerSession]
      result mustBe emptyConsumerSession
    }

    s"convert a $ConsumerSessionClsName with 1 instance to bytes" in {
      val bytes = sessionSerde.serialize("", nonEmptyConsumerSession)
      bytes must not be empty
    }

    s"convert bytes to a $ConsumerSessionClsName with 1 instance" in {
      val bytes  = sessionSerde.serialize("", nonEmptyConsumerSession)
      val result = sessionSerde.deserialize("", bytes)

      result mustBe a[ConsumerSession]
      result mustBe nonEmptyConsumerSession
    }

    s"convert a $ProducerSessionClsName with no instances to bytes" in {
      val bytes = sessionSerde.serialize("", emptyProducerSession)
      bytes must not be empty
    }

    s"convert bytes to a $ProducerSessionClsName with no instances" in {
      val bytes  = sessionSerde.serialize("", emptyProducerSession)
      val result = sessionSerde.deserialize("", bytes)

      result mustBe a[ProducerSession]
    }

    s"convert a $ProducerSessionClsName with 1 instance to bytes" in {
      val bytes = sessionSerde.serialize("", nonEmptyProducerSession)
      bytes must not be empty
    }

    s"convert bytes to a $ProducerSessionClsName with 1 instance" in {
      val bytes  = sessionSerde.serialize("", nonEmptyProducerSession)
      val result = sessionSerde.deserialize("", bytes)

      result mustBe a[ProducerSession]
      result mustBe nonEmptyProducerSession
    }
  }

}
