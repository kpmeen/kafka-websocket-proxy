package net.scalytica.kafka.wsproxy.web

import akka.http.scaladsl.model.ContentTypes._
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.server._
import akka.http.scaladsl.testkit.RouteTestTimeout
import net.scalytica.test._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Minutes, Span}
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{CustomEitherValues, OptionValues}

import scala.concurrent.duration._

class StatusRoutesSpec
    extends AnyWordSpec
    with CustomEitherValues
    with OptionValues
    with ScalaFutures
    with WsProxyKafkaSpec
    with MockOpenIdServer {

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(2, Minutes))

  implicit val timeout = RouteTestTimeout(20 seconds)

  import TestServerRoutes.serverRejectionHandler

  "The status routes" should {

    "respond with OK to the healthcheck endpoint" in
      plainServerContext() { case (_, _, route) =>
        Get("/healthcheck") ~> Route.seal(route) ~> check {
          status mustBe OK
        }
      }

    "ignore basic auth header when not enabled" in
      plainServerContext() { case (_, _, route) =>
        Get("/healthcheck") ~>
          addCredentials(basicHttpCreds) ~>
          Route.seal(route) ~> check {
            status mustBe OK
            responseEntity.contentType mustBe `application/json`
          }
      }

    "return OK to the healthcheck endpoint when basic auth is enabled" in
      secureServerContext(useServerBasicAuth = true) { case (_, _, route) =>
        Get("/healthcheck") ~> addCredentials(basicHttpCreds) ~> Route.seal(
          route
        ) ~> check {
          status mustBe OK
        }
      }

    "return 401 when accessing the healthcheck endpoint with invalid basic " +
      "auth credentials" in secureServerContext(useServerBasicAuth = true) {
        case (_, _, route) =>
          Get("/healthcheck") ~> addCredentials(
            invalidBasicHttpCreds
          ) ~> Route.seal(route) ~> check {
            status mustBe Unauthorized
          }
      }

    "return OK when server is secured but bypassed for the healthcheck " +
      "endpoint" in secureServerContext(
        useServerBasicAuth = true,
        secureHealthCheckEndpoint = false
      ) { case (_, _, route) =>
        Get("/healthcheck") ~> Route.seal(route) ~> check {
          status mustBe OK
        }
      }

    "return OK from the healthcheck endpoint when secured with a valid" +
      " bearer token" in withOpenIdConnectServerAndToken(useJwtCreds = false) {
        case (_, _, _, cfg, token) =>
          secureServerContext(serverOpenIdCfg = Option(cfg)) {
            case (_, _, route) =>
              Get("/healthcheck") ~> addCredentials(
                token.bearerToken
              ) ~> Route.seal(route) ~> check {
                status mustBe OK
                responseEntity.contentType mustBe `application/json`
              }
          }
      }

    "return 401 when accessing the healthcheck with an invalid bearer token" in
      withOpenIdConnectServerAndClient(useJwtCreds = false) {
        case (_, _, _, cfg) =>
          secureServerContext(serverOpenIdCfg = Option(cfg)) {
            case (_, _, route) =>
              Get("/healthcheck") ~> addCredentials(
                OAuth2BearerToken("invalid-token")
              ) ~> Route.seal(route) ~> check {
                status mustBe Unauthorized
              }
          }
      }
  }

}
