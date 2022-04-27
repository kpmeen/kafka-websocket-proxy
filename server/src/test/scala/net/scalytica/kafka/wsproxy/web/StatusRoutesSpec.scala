package net.scalytica.kafka.wsproxy.web

import akka.http.scaladsl.model.ContentTypes._
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.headers.{
  `WWW-Authenticate`,
  HttpChallenge,
  OAuth2BearerToken
}
import akka.http.scaladsl.server._
import akka.http.scaladsl.testkit.RouteTestTimeout
import io.circe.parser._
import net.scalytica.kafka.wsproxy.codecs.Decoders.brokerInfoDecoder
import net.scalytica.kafka.wsproxy.models.BrokerInfo
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

    "return the kafka cluster info" in
      plainServerContext() { case (kcfg, _, route) =>
        Get("/kafka/cluster/info") ~> Route.seal(route) ~> check {
          status mustBe OK
          responseEntity.contentType mustBe `application/json`

          val ci = parse(responseAs[String])
            .map(_.as[Seq[BrokerInfo]])
            .flatMap(identity)
            .rightValue

          ci must have size 1
          ci.headOption.value mustBe BrokerInfo(
            id = 0,
            host = "localhost",
            port = kcfg.kafkaPort,
            rack = None
          )
        }
      }

    "respond with OK to the healthcheck endpoint" in
      plainServerContext() { case (_, _, route) =>
        Get("/healthcheck") ~> Route.seal(route) ~> check {
          status mustBe OK
        }
      }

    "ignore basic auth header when not enabled" in
      plainServerContext() { case (_, _, route) =>
        Get("/kafka/cluster/info") ~> addCredentials(basicHttpCreds) ~> Route
          .seal(route) ~> check {
          status mustBe OK
          responseEntity.contentType mustBe `application/json`
        }
      }

    "return the kafka cluster info when secured with basic auth" in
      secureServerContext(useServerBasicAuth = true) { case (kcfg, _, route) =>
        Get("/kafka/cluster/info") ~> addCredentials(basicHttpCreds) ~> Route
          .seal(route) ~> check {
          status mustBe OK
          responseEntity.contentType mustBe `application/json`

          val ci = parse(responseAs[String])
            .map(_.as[Seq[BrokerInfo]])
            .flatMap(identity)
            .rightValue

          ci must have size 1
          ci.headOption.value mustBe BrokerInfo(
            id = 0,
            host = "localhost",
            port = kcfg.kafkaPort,
            rack = None
          )
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

    "return 401 when accessing kafka cluster info with invalid basic auth" in
      secureServerContext(useServerBasicAuth = true) { case (_, _, route) =>
        Get("/kafka/cluster/info") ~> addCredentials(
          invalidBasicHttpCreds
        ) ~> Route.seal(route) ~> check {
          status mustBe Unauthorized
          val authHeader = header[`WWW-Authenticate`].get
          authHeader.challenges.head mustEqual HttpChallenge(
            scheme = "Basic",
            realm = Some(basicAuthRealm),
            params = Map("charset" -> "UTF-8")
          )
        }
      }

    "return the kafka cluster info when secured with OpenID Connect" in
      withOpenIdConnectServerAndToken(useJwtKafkaCreds = false) {
        case (_, _, _, cfg, token) =>
          secureServerContext(serverOpenIdCfg = Option(cfg)) {
            case (kcfg, _, route) =>
              Get("/kafka/cluster/info") ~> addCredentials(
                token.bearerToken
              ) ~> Route.seal(route) ~> check {
                status mustBe OK
                responseEntity.contentType mustBe `application/json`

                val ci = parse(responseAs[String])
                  .map(_.as[Seq[BrokerInfo]])
                  .flatMap(identity)
                  .rightValue

                ci must have size 1
                ci.headOption.value mustBe BrokerInfo(
                  id = 0,
                  host = "localhost",
                  port = kcfg.kafkaPort,
                  rack = None
                )
              }
          }
      }

    "return 401 when accessing kafka cluster info with invalid bearer token" in
      withOpenIdConnectServerAndClient(useJwtKafkaCreds = false) {
        case (_, _, _, cfg) =>
          secureServerContext(serverOpenIdCfg = Option(cfg)) {
            case (_, _, route) =>
              Get("/kafka/cluster/info") ~> addCredentials(
                OAuth2BearerToken("invalid-token")
              ) ~> Route.seal(route) ~> check {
                status mustBe Unauthorized
              }
          }
      }
  }

}
