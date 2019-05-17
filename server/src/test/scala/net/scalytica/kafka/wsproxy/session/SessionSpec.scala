package net.scalytica.kafka.wsproxy.session

import net.scalytica.kafka.wsproxy.models.{WsClientId, WsGroupId, WsServerId}
import net.scalytica.test.SessionOpResultValues
import org.scalatest.{MustMatchers, OptionValues, WordSpec}

class SessionSpec
    extends WordSpec
    with MustMatchers
    with SessionOpResultValues
    with OptionValues {

  "A session" should {

    "be initialised with consumer group and default consumer limit" in {
      assertCompiles("""Session(WsGroupId("foo"))""")
    }

    "be initialised with consumer group and limit" in {
      assertCompiles("""Session(WsGroupId("foo"), 3)""")
    }

    "be initialised with consumer group, limit and consumer instances" in {
      assertCompiles(
        """Session(WsGroupId("foo"), Set(ConsumerInstance(WsClientId("bar"), WsServerId("node-123"))), 1)""" // scalastyle:ignore
      )
    }

    "allow adding a new consumer using base arguments" in {
      val s1 = Session(WsGroupId("s1"))
      val s2 = s1.addConsumer(WsClientId("c1"), WsServerId("n1")).value
      val s3 = s2.addConsumer(WsClientId("c2"), WsServerId("n2")).value

      s1.consumers mustBe empty
      s2.consumers must have size 1
      s3.consumers must have size 2
    }

    "allow adding a new consumer instance" in {
      val s1 = Session(WsGroupId("s1"))
        .addConsumer(WsClientId("c1"), WsServerId("n1"))
        .value
      val ci = ConsumerInstance(WsClientId("c2"), WsServerId("n2"))
      val s2 = s1.addConsumer(ci).value

      s1.consumers must have size 1
      s2.consumers must have size 2

      s2.consumers must contain(ci)
    }

    "return the same session if an existing consumer is added" in {
      val s1 =
        Session(WsGroupId("s1"))
          .addConsumer(WsClientId("c1"), WsServerId("n1"))
          .value
          .addConsumer(WsClientId("c2"), WsServerId("n2"))
          .value
      val s2 = s1.addConsumer(WsClientId("c2"), WsServerId("n2")).value

      s2 mustBe s1
    }

    "remove a consumer based on its consumer id" in {
      val s1 =
        Session(WsGroupId("s1"))
          .addConsumer(WsClientId("c1"), WsServerId("n1"))
          .value
          .addConsumer(WsClientId("c2"), WsServerId("n2"))
          .value
      val s2 = s1.removeConsumer(WsClientId("c1")).value

      s2.consumers must have size 1
      s2.consumers.headOption.value.id mustBe WsClientId("c2")
    }

    "return the same session when removing a non-existing consumer id" in {
      val s1 =
        Session(WsGroupId("s1"))
          .addConsumer(WsClientId("c1"), WsServerId("n1"))
          .value
          .addConsumer(WsClientId("c2"), WsServerId("n2"))
          .value
      val s2 = s1.removeConsumer(WsClientId("c0")).value

      s2 mustBe s1
    }

    "return true when the session can have more consumers" in {
      Session(WsGroupId("s1"))
        .addConsumer(WsClientId("c1"), WsServerId("n1"))
        .value
        .canOpenSocket mustBe true
    }

    "return false when the session can not have more consumers" in {
      Session(WsGroupId("s1"))
        .addConsumer(WsClientId("c1"), WsServerId("n1"))
        .value
        .addConsumer(WsClientId("c2"), WsServerId("n2"))
        .value
        .canOpenSocket mustBe false
    }

    "not allowing adding more consumer instances when limit is reached" in {
      val s1 =
        Session(WsGroupId("s1"))
          .addConsumer(WsClientId("c1"), WsServerId("n1"))
          .value
          .addConsumer(WsClientId("c2"), WsServerId("n2"))
          .value
      s1.addConsumer(WsClientId("c3"), WsServerId("n3")) mustBe Session
        .ConsumerLimitReached(s1)
    }

  }

}
