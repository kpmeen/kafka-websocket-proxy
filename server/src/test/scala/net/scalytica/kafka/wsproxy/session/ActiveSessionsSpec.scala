package net.scalytica.kafka.wsproxy.session

import net.scalytica.kafka.wsproxy.models.WsGroupId
import org.scalatest.{EitherValues, MustMatchers, OptionValues, WordSpec}

// scalastyle:off magic.number
class ActiveSessionsSpec
    extends WordSpec
    with MustMatchers
    with OptionValues
    with EitherValues {

  val s1 = Session(WsGroupId("c1"))
  val s2 = Session(WsGroupId("c2"))
  val s3 = Session(WsGroupId("c3"))

  val expected = Map(
    s1.consumerGroupId -> s1,
    s2.consumerGroupId -> s2,
    s3.consumerGroupId -> s3
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

    "find a session based on consumer group id" in {
      as.find(WsGroupId("c2")).value mustBe s2
    }

    "return none if a session is not found" in {
      as.find(WsGroupId("c10")) mustBe empty
    }

    "add a new session" in {
      val ns  = Session(WsGroupId("c4"), 4)
      val as2 = as.add(ns)

      val res = as2.right.value.sessions
      res must contain allElementsOf (expected + (ns.consumerGroupId -> ns))
    }

    "update an existing session with a new session object" in {
      val ns  = Session(WsGroupId("c2"), 4)
      val as2 = as.updateSession(WsGroupId("c2"), ns)

      as2.right.value must not be as

      as2.right.value.find(WsGroupId("c2")).value mustBe ns
    }

    "add a new session if it didn't previously exist" in {
      val ns  = Session(WsGroupId("c4"), 4)
      val as2 = as.updateSession(WsGroupId("c4"), ns)

      val res = as2.right.value.sessions
      res must contain allElementsOf (expected + (ns.consumerGroupId -> ns))
    }

    "remove a session" in {
      val as2 = as.removeSession(WsGroupId("c2"))

      as2.right.value.sessions must contain allElementsOf (expected - WsGroupId(
        "c2"
      ))
    }
  }

}
