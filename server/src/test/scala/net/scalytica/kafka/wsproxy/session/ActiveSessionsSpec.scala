package net.scalytica.kafka.wsproxy.session

import net.scalytica.kafka.wsproxy.models.WsGroupId
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{CustomEitherValues, OptionValues}

// scalastyle:off magic.number
class ActiveSessionsSpec
    extends AnyWordSpec
    with Matchers
    with OptionValues
    with CustomEitherValues {

  val s1 = ConsumerSession(SessionId("s1"), WsGroupId("c1"))
  val s2 = ProducerSession(SessionId("s2"))
  val s3 = ConsumerSession(SessionId("s3"), WsGroupId("c3"))

  val expected = Map(
    s1.sessionId -> s1,
    s2.sessionId -> s2,
    s3.sessionId -> s3
  )

  val as = ActiveSessions(expected)

  "An ActiveSessions" should {

    "be initialised with an empty session map" in {
      ActiveSessions().sessions mustBe empty
    }

    "be initialised with a non-empty session map" in {
      as.sessions must contain allElementsOf expected
    }

    "be initialised with a list of sessions" in {
      ActiveSessions(s1, s2, s3).sessions must contain allElementsOf expected
    }

    "return none if a session is not found" in {
      as.find(SessionId("s10")) mustBe empty
    }

    "add a new session" in {
      val ns  = ConsumerSession(SessionId("s4"), WsGroupId("c4"), 4)
      val as2 = as.add(ns)

      val res = as2.rightValue.sessions
      res must contain allElementsOf (expected + (ns.sessionId -> ns))
    }

    "update an existing session with a new session object" in {
      val ns  = ConsumerSession(SessionId("s2"), WsGroupId("c2"), 4)
      val as2 = as.updateSession(SessionId("s2"), ns)

      as2.rightValue must not be as

      as2.rightValue.find(SessionId("s2")).value mustBe ns
    }

    "add a new session if it didn't previously exist" in {
      val ns  = ConsumerSession(SessionId("s4"), WsGroupId("c4"), 4)
      val as2 = as.updateSession(SessionId("s4"), ns)

      val res = as2.rightValue.sessions
      res must contain allElementsOf (expected + (ns.sessionId -> ns))
    }

    "remove a session" in {
      val as2 = as.removeSession(SessionId("s2"))

      as2.rightValue.sessions must contain allElementsOf (
        expected - SessionId("s2")
      )
    }
  }

}
