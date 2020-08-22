package net.scalytica.kafka.wsproxy.auth

import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec

class TokenRequestSpec extends AnyWordSpec with Matchers {

  val tokenRequest = TokenRequest(
    "client identifier",
    "super secret",
    "the audience",
    "client_credentials"
  )

  // scalastyle:off
  val expectedJsonString =
    """{"client_id":"client identifier","client_secret":"super secret","audience":"the audience","grant_type":"client_credentials"}"""
  // scalastyle:on

  "The TokenRequest" should {

    "render the correct JSON" in {
      tokenRequest.jsonString mustBe expectedJsonString
    }

  }

}
