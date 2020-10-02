package net.scalytica.kafka.wsproxy.auth

import akka.util.Timeout
import net.scalytica.test.{MockOpenIdServer, WsProxyKafkaSpec}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.must.Matchers
import org.scalatest.time.{Minute, Span}
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{OptionValues, TryValues}
import pdi.jwt._

import scala.concurrent.duration._

class OpenIdClientSpec
    extends AnyWordSpec
    with WsProxyKafkaSpec
    with Matchers
    with ScalaFutures
    with OptionValues
    with TryValues
    with MockOpenIdServer {

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(1, Minute))

  implicit val timeout: Timeout = 10 seconds

  "The OAuth2 client" should {

    "fetch the .well-known openid-connect configuration" in
      withEmbeddedOpenIdConnectServerAndClient() {
        case (host, port, client, _) =>
          client.wellKnownOidcConfig mustBe openIdConnectConfig(host, port)
      }

    "fetch openid-connect config and then the jwks config" in
      withEmbeddedOpenIdConnectServerAndClient() { case (_, _, client, _) =>
        val oidc     = client.wellKnownOidcConfig
        val provider = new UrlJwkProvider(oidc.jwksUri, enforceHttps = false)
        val res      = provider.load().futureValue

        res must have size 1
        val jwk = res.headOption.value

        jwk.kty mustBe keyAlgorithm
        jwk.kid.value mustBe keyId
        jwk.alg.value mustBe JwtAlgorithm.RS256.name
        jwk.use.value mustBe "sig"
        jwk.x5c mustBe empty
        jwk.x5t mustBe empty
        jwk.x5u mustBe empty
        jwk.n must not be empty
        jwk.e must not be empty
      }

    "get a bearer token" in withEmbeddedOpenIdConnectServerAndClient() {
      case (_, _, client, _) =>
        val res = client
          .generateToken(oidClientId, oidClientSecret, oidAudience, oidGrantTpe)
          .futureValue
          .value

        res.tokenType mustBe "Bearer"
        res.bearerToken.value must not be empty
        res.expiresIn mustBe expirationMillis
        res.refreshToken mustBe None
    }

    "validate a bearer token" in withEmbeddedOpenIdConnectServerAndToken() {
      case (host, port, client, _, token) =>
        val bearerToken = token.bearerToken

        val response = client.validate(bearerToken).futureValue
        val tokenRes = response.success.value

        tokenRes.audience.value must contain(oidAudience)
        tokenRes.issuer.value mustBe s"http://$host:$port"
        tokenRes.expiration.value mustBe expirationMillis
        tokenRes.issuedAt.value mustBe issuedAtMillis
    }

  }

}
