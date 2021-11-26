package net.scalytica.kafka.wsproxy.session

import net.scalytica.kafka.wsproxy.models.{WsClientId, WsGroupId, WsServerId}
import net.scalytica.test.SessionOpResultValues
import org.scalatest.OptionValues
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec

class SessionSpec
    extends AnyWordSpec
    with Matchers
    with SessionOpResultValues
    with OptionValues {

  "A session" when {

    "used for a consumer" should {

      "be initialised with consumer group and default max connection" in {
        assertCompiles("""ConsumerSession(SessionId("foo"),WsGroupId("foo"))""")
      }

      "be initialised with consumer group and max connections" in {
        assertCompiles(
          """ConsumerSession(SessionId("foo"), WsGroupId("foo"), 3)"""
        )
      }

      "be initialised with consumer group, max connections and" +
        " consumer instances" in {
          assertCompiles(
            """ConsumerSession(
            |  sessionId = SessionId("foo"),
            |  groupId = WsGroupId("foo"),
            |  maxConnections = 2,
            |  instances = Set(
            |    ConsumerInstance(
            |      clientId = WsClientId("bar"),
            |      groupId = WsGroupId("foo"),
            |      serverId = WsServerId("node-123")
            |    )
            |  ))""".stripMargin
          )
        }

      "successfully initialise an instance with a consumer instance" in {
        ConsumerSession(
          sessionId = SessionId("foo"),
          groupId = WsGroupId("foo"),
          maxConnections = 1,
          instances = Set(
            ConsumerInstance(
              clientId = WsClientId("bar"),
              groupId = WsGroupId("foo"),
              serverId = WsServerId("node-123")
            )
          )
        )
        succeed
      }

      "fail to initialise when instances contain a producer instance" in {
        assertThrows[IllegalArgumentException] {
          ConsumerSession(
            sessionId = SessionId("foo"),
            groupId = WsGroupId("foo"),
            maxConnections = 1,
            instances = Set(
              ProducerInstance(
                clientId = WsClientId("bar"),
                serverId = WsServerId("node-123")
              )
            )
          )
        }
      }

      "allow adding a new consumer using base arguments" in {
        val s1 = ConsumerSession(SessionId("s1"), WsGroupId("s1"))
        val s2 = s1.addInstance(WsClientId("c1"), WsServerId("n1")).value
        val s3 = s2.addInstance(WsClientId("c2"), WsServerId("n2")).value

        s1.instances mustBe empty
        s2.instances must have size 1
        s3.instances must have size 2
      }

      "not allow adding a producer instance" in {
        val gid = WsGroupId("s1")
        val sid = WsServerId("n1")
        val s1  = ConsumerSession(SessionId(gid), gid)

        s1.instances mustBe empty

        val ci = ConsumerInstance(WsClientId("c1"), gid, sid)

        val s2 = s1.addInstance(ci).value

        s2.instances must have size 1

        val pi = ProducerInstance(WsClientId("c2"), sid)
        s2.addInstance(pi) mustBe an[InstanceTypeForSessionIncorrect]
      }

      "allow adding a new consumer instance" in {
        val s1 = ConsumerSession(SessionId("s1"), WsGroupId("s1"))
          .addInstance(WsClientId("c1"), WsServerId("n1"))
          .value
        val ci =
          ConsumerInstance(WsClientId("c2"), WsGroupId("s1"), WsServerId("n2"))
        val s2 = s1.addInstance(ci).value

        s1.instances must have size 1
        s2.instances must have size 2

        s2.instances must contain(ci)
      }

      "return the same session if an existing consumer is added" in {
        val s1 =
          ConsumerSession(SessionId("s1"), WsGroupId("s1"))
            .addInstance(WsClientId("c1"), WsServerId("n1"))
            .value
            .addInstance(WsClientId("c2"), WsServerId("n2"))
            .value
        val s2 = s1.addInstance(WsClientId("c2"), WsServerId("n2")).value

        s2 mustBe s1
      }

      "remove a consumer based on its client id" in {
        val s1 =
          ConsumerSession(SessionId("s1"), WsGroupId("s1"))
            .addInstance(WsClientId("c1"), WsServerId("n1"))
            .value
            .addInstance(WsClientId("c2"), WsServerId("n2"))
            .value
        val s2 = s1.removeInstance(WsClientId("c1")).value

        s2.instances must have size 1
        s2.instances.headOption.value.clientId mustBe WsClientId("c2")
      }

      "return the same session when removing a non-existing consumer id" in {
        val s1 =
          ConsumerSession(SessionId("s1"), WsGroupId("s1"))
            .addInstance(WsClientId("c1"), WsServerId("n1"))
            .value
            .addInstance(WsClientId("c2"), WsServerId("n2"))
            .value
        val s2 = s1.removeInstance(WsClientId("c0")).value

        s2 mustBe s1
      }

      "return true when the session can have more consumers" in {
        ConsumerSession(SessionId("s1"), WsGroupId("s1"))
          .addInstance(WsClientId("c1"), WsServerId("n1"))
          .value
          .canOpenSocket mustBe true
      }

      "return false when the session can not have more consumers" in {
        ConsumerSession(SessionId("s1"), WsGroupId("s1"))
          .addInstance(WsClientId("c1"), WsServerId("n1"))
          .value
          .addInstance(WsClientId("c2"), WsServerId("n2"))
          .value
          .canOpenSocket mustBe false
      }

      "not allowing adding more consumer instances when max connections " +
        "limit is reached" in {
          val s1 =
            ConsumerSession(SessionId("s1"), WsGroupId("s1"))
              .addInstance(WsClientId("c1"), WsServerId("n1"))
              .value
              .addInstance(WsClientId("c2"), WsServerId("n2"))
              .value
          s1.addInstance(
            WsClientId("c3"),
            WsServerId("n3")
          ) mustBe InstanceLimitReached(s1)
        }

    }

    "used for a producer" should {

      "be initialised with a session id and default max connections" in {
        assertCompiles("""ProducerSession(SessionId("foo"))""")
      }

      "be initialised with a session id and max connections" in {
        assertCompiles("""ProducerSession(SessionId("foo"), 3)""")
      }

      "be initialised with a session id, max connections and producer" +
        " instances" in {
          assertCompiles(
            """ProducerSession(
            |  sessionId = SessionId("foo"),
            |  maxConnections = 2,
            |  instances = Set(
            |    ProducerInstance(
            |      clientId = WsClientId("bar"),
            |      serverId = WsServerId("node-123")
            |    )
            |  ))""".stripMargin
          )
        }

      "successfully initialise an instance with a producer instance" in {
        ProducerSession(
          sessionId = SessionId("foo"),
          maxConnections = 2,
          instances = Set(
            ProducerInstance(
              clientId = WsClientId("bar"),
              serverId = WsServerId("node-123")
            )
          )
        )
        succeed
      }

      "fail to initialise when instances contain a consumer instance" in {
        assertThrows[IllegalArgumentException] {
          ProducerSession(
            sessionId = SessionId("foo"),
            maxConnections = 2,
            instances = Set(
              ConsumerInstance(
                clientId = WsClientId("bar"),
                groupId = WsGroupId("foo"),
                serverId = WsServerId("node-123")
              )
            )
          )
        }
      }

      "allow adding a new producer using base arguments" in {
        val s1 =
          ProducerSession(sessionId = SessionId("s1"), maxConnections = 2)
        val s2 = s1.addInstance(WsClientId("c1"), WsServerId("n1")).value
        val s3 = s2.addInstance(WsClientId("c2"), WsServerId("n2")).value

        s1.instances mustBe empty
        s2.instances must have size 1
        s3.instances must have size 2
      }

      "not allow adding a consumer instance" in {
        val sid = WsServerId("n1")
        val s1  = ProducerSession(SessionId("foo"))

        s1.instances mustBe empty

        val ci = ProducerInstance(WsClientId("c1"), sid)

        val s2 = s1.addInstance(ci).value

        s2.instances must have size 1

        val pi = ConsumerInstance(WsClientId("c2"), WsGroupId("s1"), sid)
        s2.addInstance(pi) mustBe an[InstanceTypeForSessionIncorrect]
      }

      "allow adding a new producer instance" in {
        val s1 = ProducerSession(
          sessionId = SessionId("s1"),
          maxConnections = 2
        ).addInstance(WsClientId("c1"), WsServerId("n1")).value
        val ci = ProducerInstance(WsClientId("c2"), WsServerId("n2"))
        val s2 = s1.addInstance(ci).value

        s1.instances must have size 1
        s2.instances must have size 2

        s2.instances must contain(ci)
      }

      "return the same session if an existing producer is added" in {
        val s1 =
          ProducerSession(sessionId = SessionId("s1"), maxConnections = 2)
            .addInstance(WsClientId("c1"), WsServerId("n1"))
            .value
            .addInstance(WsClientId("c2"), WsServerId("n2"))
            .value
        val s2 = s1.addInstance(WsClientId("c2"), WsServerId("n2")).value

        s2 mustBe s1
      }

      "remove a producer based on its client id" in {
        val s1 =
          ProducerSession(sessionId = SessionId("s1"), maxConnections = 2)
            .addInstance(WsClientId("c1"), WsServerId("n1"))
            .value
            .addInstance(WsClientId("c2"), WsServerId("n2"))
            .value
        val s2 = s1.removeInstance(WsClientId("c1")).value

        s2.instances must have size 1
        s2.instances.headOption.value.clientId mustBe WsClientId("c2")
      }

      "return the same session when removing a non-existing client id" in {
        val s1 =
          ProducerSession(sessionId = SessionId("s1"), maxConnections = 2)
            .addInstance(WsClientId("c1"), WsServerId("n1"))
            .value
            .addInstance(WsClientId("c2"), WsServerId("n2"))
            .value
        val s2 = s1.removeInstance(WsClientId("c0")).value

        s2 mustBe s1
      }

      "return true when the session can have more producers" in {
        ProducerSession(sessionId = SessionId("s1"), maxConnections = 2)
          .addInstance(WsClientId("c1"), WsServerId("n1"))
          .value
          .canOpenSocket mustBe true
      }

      "return false when the session can not have more producers" in {
        ProducerSession(sessionId = SessionId("s1"), maxConnections = 2)
          .addInstance(WsClientId("c1"), WsServerId("n1"))
          .value
          .addInstance(WsClientId("c2"), WsServerId("n2"))
          .value
          .canOpenSocket mustBe false
      }

      "not allowing adding more producer instances when limit is reached" in {
        val s1 =
          ProducerSession(sessionId = SessionId("s1"), maxConnections = 2)
            .addInstance(WsClientId("c1"), WsServerId("n1"))
            .value
            .addInstance(WsClientId("c2"), WsServerId("n2"))
            .value
        s1.addInstance(
          WsClientId("c3"),
          WsServerId("n3")
        ) mustBe InstanceLimitReached(s1)
      }

    }

  }
}
