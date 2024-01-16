package net.scalytica.kafka.wsproxy.web

import org.apache.pekko.http.scaladsl.model.ContentTypes._
import org.apache.pekko.http.scaladsl.model.StatusCode
import org.apache.pekko.http.scaladsl.model.StatusCodes._
import org.apache.pekko.http.scaladsl.model.headers.{
  HttpCredentials,
  OAuth2BearerToken
}
import org.apache.pekko.http.scaladsl.server._
import org.apache.pekko.http.scaladsl.testkit.RouteTestTimeout
import net.scalytica.kafka.wsproxy.auth.OpenIdClient
import net.scalytica.kafka.wsproxy.config.Configuration.AppCfg
import net.scalytica.test._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Minutes, Span}
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{Assertion, CustomEitherValues, OptionValues}

import scala.concurrent.duration._

class StatusRoutesSpec
    extends AnyWordSpec
    with TestStatusRoutes
    with CustomEitherValues
    with OptionValues
    with ScalaFutures
    with WsProxyKafkaSpec
    with MockOpenIdServer {

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(2, Minutes))

  implicit val timeout = RouteTestTimeout(20 seconds)

  private[this] def assertHealthCheck(
      maybeCredentials: Option[HttpCredentials],
      expectedStatus: StatusCode
  )(
      implicit appCfg: AppCfg,
      maybeOidClient: Option[OpenIdClient]
  ): Assertion = {
    val root = Get("/healthcheck")

    maybeCredentials.map(c => root ~> addCredentials(c)).getOrElse(root) ~>
      Route.seal(statusRoutes) ~>
      check {
        status mustBe expectedStatus
        responseEntity.contentType mustBe `application/json`
      }

  }

  private[this] def assertNoCredentials(
      expectedStatus: StatusCode
  )(
      implicit appCfg: AppCfg,
      maybeOidClient: Option[OpenIdClient]
  ): Assertion = assertHealthCheck(None, expectedStatus)

  private[this] def assertWithCredentials(
      credentials: HttpCredentials,
      expectedStatus: StatusCode
  )(
      implicit appCfg: AppCfg,
      maybeOidClient: Option[OpenIdClient]
  ): Assertion = assertHealthCheck(Some(credentials), expectedStatus)

  "The status routes" when {

    "unsecured" should {

      "return OK" in
        plainContextNoWebSockets() { (_, cfg) =>
          implicit val appCfg                          = cfg
          implicit val oidClient: Option[OpenIdClient] = None

          assertNoCredentials(OK)
        }

      "ignore security headers and return OK" in
        plainContextNoWebSockets() { (_, cfg) =>
          implicit val appCfg                          = cfg
          implicit val oidClient: Option[OpenIdClient] = None

          assertWithCredentials(basicHttpCreds, OK)
        }
    }

    "secured with basic auth" should {

      "return OK when using valid credentials" in
        secureContextNoWebSockets(useServerBasicAuth = true) { case (_, cfg) =>
          implicit val appCfg    = cfg
          implicit val oidClient = None

          assertWithCredentials(basicHttpCreds, OK)
        }

      "return 401 when using invalid credentials" in
        secureContextNoWebSockets(useServerBasicAuth = true) { case (_, cfg) =>
          implicit val appCfg    = cfg
          implicit val oidClient = None

          assertWithCredentials(invalidBasicHttpCreds, Unauthorized)
        }

      "return OK when security is bypassed" in
        secureContextNoWebSockets(
          useServerBasicAuth = true,
          secureHealthCheckEndpoint = false
        ) { case (_, cfg) =>
          implicit val appCfg    = cfg
          implicit val oidClient = None

          assertNoCredentials(OK)
          assertWithCredentials(invalidBasicHttpCreds, OK)
        }
    }

    "secured with OpenID Connect" should {

      "return OK when using a valid bearer token" in
        withOpenIdConnectServerAndToken(useJwtCreds = false) {
          case (_, _, _, oidcCfg, token) =>
            secureContextNoWebSockets(serverOpenIdCfg = Option(oidcCfg)) {
              case (_, cfg) =>
                implicit val appCfg    = cfg
                implicit val oidClient = Option(OpenIdClient(cfg))

                assertWithCredentials(token.bearerToken, OK)
            }
        }

      "return 401 when using an invalid bearer token" in
        withOpenIdConnectServerAndClient(useJwtCreds = false) {
          case (_, _, _, oidcCfg) =>
            secureContextNoWebSockets(serverOpenIdCfg = Option(oidcCfg)) {
              case (_, cfg) =>
                implicit val appCfg    = cfg
                implicit val oidClient = Option(OpenIdClient(cfg))

                assertWithCredentials(
                  credentials = OAuth2BearerToken("invalid-token"),
                  expectedStatus = Unauthorized
                )
            }
        }

      "return OK when security is bypassed" in
        withOpenIdConnectServerAndToken(useJwtCreds = false) {
          case (_, _, _, oidcCfg, _) =>
            secureContextNoWebSockets(
              serverOpenIdCfg = Option(oidcCfg),
              secureHealthCheckEndpoint = false
            ) { case (_, cfg) =>
              implicit val appCfg    = cfg
              implicit val oidClient = Option(OpenIdClient(cfg))

              assertNoCredentials(OK)
              assertWithCredentials(OAuth2BearerToken("invalid-token"), OK)
            }
        }
    }

    "secured with both Basic Auth and OpenID Connect" should {

      "return 401 when using basic auth credentials" in
        withOpenIdConnectServerAndToken(useJwtCreds = false) {
          case (_, _, _, oidcCfg, _) =>
            secureContextNoWebSockets(
              useServerBasicAuth = true,
              serverOpenIdCfg = Option(oidcCfg)
            ) { case (_, cfg) =>
              implicit val appCfg    = cfg
              implicit val oidClient = Option(OpenIdClient(cfg))

              assertWithCredentials(basicHttpCreds, Unauthorized)
            }
        }

      "return OK when using a valid bearer token" in
        withOpenIdConnectServerAndToken(useJwtCreds = false) {
          case (_, _, _, oidcCfg, token) =>
            secureContextNoWebSockets(
              useServerBasicAuth = true,
              serverOpenIdCfg = Option(oidcCfg)
            ) { case (_, cfg) =>
              implicit val appCfg    = cfg
              implicit val oidClient = Option(OpenIdClient(cfg))

              assertWithCredentials(token.bearerToken, OK)
            }
        }

      "return 401 when using an invalid bearer token" in
        withOpenIdConnectServerAndClient(useJwtCreds = false) {
          case (_, _, _, oidcCfg) =>
            secureContextNoWebSockets(
              useServerBasicAuth = true,
              serverOpenIdCfg = Option(oidcCfg)
            ) { case (_, cfg) =>
              implicit val appCfg    = cfg
              implicit val oidClient = Option(OpenIdClient(cfg))

              assertWithCredentials(
                credentials = OAuth2BearerToken("invalid-token"),
                expectedStatus = Unauthorized
              )
            }
        }

      "return OK when security is bypassed" in
        withOpenIdConnectServerAndToken(useJwtCreds = false) {
          case (_, _, _, oidcCfg, _) =>
            secureContextNoWebSockets(
              useServerBasicAuth = true,
              serverOpenIdCfg = Option(oidcCfg),
              secureHealthCheckEndpoint = false
            ) { case (_, cfg) =>
              implicit val appCfg    = cfg
              implicit val oidClient = Option(OpenIdClient(cfg))

              assertNoCredentials(OK)
              assertWithCredentials(invalidBasicHttpCreds, OK)
              assertWithCredentials(OAuth2BearerToken("invalid-token"), OK)
            }
        }
    }
  }

}
