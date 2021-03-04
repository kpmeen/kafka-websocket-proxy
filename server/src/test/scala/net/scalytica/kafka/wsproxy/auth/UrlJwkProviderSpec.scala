package net.scalytica.kafka.wsproxy.auth

import akka.util.Timeout
import net.scalytica.test.WsProxyKafkaSpec
import org.scalatest.Inspectors.forAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.must.Matchers
import org.scalatest.time.{Minute, Span}
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{Assertion, OptionValues, TryValues}
import pdi.jwt.JwtAlgorithm

import scala.concurrent.duration._

class UrlJwkProviderSpec
    extends AnyWordSpec
    with WsProxyKafkaSpec
    with Matchers
    with ScalaFutures
    with OptionValues
    with TryValues {

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(1, Minute))

  implicit val timeout: Timeout = 3 seconds

  val validHost     = "samples.auth0.com"
  val validCfgUri   = ".well-known/jwks.json"
  val validJwksUrl1 = s"https://$validHost/$validCfgUri"
  val validJwksUrl2 = "https://www.googleapis.com/oauth2/v3/certs"

  val unresolvableHost   = "foo.bar.baz"
  val unresolvableCfgUrl = s"https://$validHost/wrong/location"

  val auth0ValidKid = "UW65QUcG827BofOEU7QXY"

  private[this] def validateJwk(jwk: Jwk): Assertion = {
    jwk.kty mustBe Jwk.PubKeyAlgo
    jwk.use.value mustBe "sig"
    jwk.alg.value mustBe JwtAlgorithm.RS256.name
    jwk.kid must not be empty
    jwk.n must not be empty
    jwk.e must not be empty
  }

  "The UrlJwkProvider" should {

    "successfully initialize" in {
      new UrlJwkProvider(validJwksUrl1)
    }

    "fail when initializing with a URL with missing https:// prefix" in {
      assertThrows[IllegalArgumentException] {
        new UrlJwkProvider(s"$validHost")
      }
    }

    "fail when initializing with a URL with unresolvable host" in {
      assertThrows[IllegalArgumentException] {
        new UrlJwkProvider(s"https://$unresolvableHost")
      }
    }

    s"successfully load jwks configuration from $validJwksUrl1" in {
      val res1 = new UrlJwkProvider(validJwksUrl1).load().futureValue

      res1 must not be empty
      forAll(res1)(validateJwk)
    }
    s"successfully load jwks configuration from $validJwksUrl2" in {
      val res2 = new UrlJwkProvider(validJwksUrl2).load().futureValue

      res2 must not be empty
      forAll(res2)(validateJwk)
    }

    "successfully get a key from the jwks configuration" in {
      val jwk = new UrlJwkProvider(validJwksUrl1)
        .get(auth0ValidKid)
        .futureValue
        .success
        .value

      validateJwk(jwk)
      jwk.kid.value mustBe auth0ValidKid
    }

    "return an empty list when the config cannot be found" in {
      new UrlJwkProvider(unresolvableCfgUrl).load().futureValue mustBe empty
    }
  }

}
