package net.scalytica.kafka.wsproxy

import akka.http.scaladsl.model.ContentTypes
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
import org.scalatest.{EitherValues, OptionValues}

import scala.concurrent.duration._

class StatusRoutesSpec
    extends AnyWordSpec
    with EitherValues
    with OptionValues
    with ScalaFutures
    with WSProxyKafkaSpec
    with MockOpenIdServer {

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(2, Minutes))

  implicit val timeout = RouteTestTimeout(20 seconds)

  import TestServerRoutes.{serverErrorHandler, serverRejectionHandler}

  "The status routes" should {

    "return the kafka cluster info" in
      withRunningKafkaOnFoundPort(embeddedKafkaConfig) { implicit kcfg =>
        implicit val wsCfg = appTestConfig(kcfg.kafkaPort)

        val (sdcStream, testRoutes) = TestServerRoutes.wsProxyRoutes
        val ctrl                    = sdcStream.run()

        Get("/kafka/cluster/info") ~> Route.seal(testRoutes) ~> check {
          status mustBe OK
          responseEntity.contentType mustBe ContentTypes.`application/json`

          val ci = parse(responseAs[String])
            .map(_.as[Seq[BrokerInfo]])
            .flatMap(identity)
            .right
            .value

          ci must have size 1
          ci.headOption.value mustBe BrokerInfo(
            id = 0,
            host = "localhost",
            port = kcfg.kafkaPort,
            rack = None
          )
        }

        ctrl.shutdown()
      }

    "ignore basic auth header when not enabled" in
      withRunningKafkaOnFoundPort(embeddedKafkaConfig) { implicit kcfg =>
        implicit val wsCfg = appTestConfig(kcfg.kafkaPort)

        val (sdcStream, testRoutes) = TestServerRoutes.wsProxyRoutes
        val ctrl                    = sdcStream.run()

        Get("/kafka/cluster/info") ~> addCredentials(basicHttpCreds) ~> Route
          .seal(testRoutes) ~> check {
          status mustBe OK
          responseEntity.contentType mustBe ContentTypes.`application/json`
        }

        ctrl.shutdown()
      }

    "return the kafka cluster info when secured with basic auth" in
      withRunningKafkaOnFoundPort(embeddedKafkaConfig) { implicit kcfg =>
        implicit val wsCfg =
          appTestConfig(kafkaPort = kcfg.kafkaPort, useBasicAuth = true)

        val (sdcStream, testRoutes) = TestServerRoutes.wsProxyRoutes
        val ctrl                    = sdcStream.run()

        Get("/kafka/cluster/info") ~> addCredentials(basicHttpCreds) ~> Route
          .seal(testRoutes) ~> check {
          status mustBe OK
          responseEntity.contentType mustBe ContentTypes.`application/json`

          val ci = parse(responseAs[String])
            .map(_.as[Seq[BrokerInfo]])
            .flatMap(identity)
            .right
            .value

          ci must have size 1
          ci.headOption.value mustBe BrokerInfo(
            id = 0,
            host = "localhost",
            port = kcfg.kafkaPort,
            rack = None
          )
        }

        ctrl.shutdown()
      }

    "return 401 when accessing kafka cluster info with invalid basic auth" in
      withRunningKafkaOnFoundPort(embeddedKafkaConfig) { implicit kcfg =>
        implicit val wsCfg =
          appTestConfig(kafkaPort = kcfg.kafkaPort, useBasicAuth = true)

        val (sdcStream, testRoutes) = TestServerRoutes.wsProxyRoutes
        val ctrl                    = sdcStream.run()

        Get("/kafka/cluster/info") ~> addCredentials(
          invalidBasicHttpCreds
        ) ~> Route.seal(testRoutes) ~> check {
          status mustBe Unauthorized
          val authHeader = header[`WWW-Authenticate`].get
          authHeader.challenges.head mustEqual HttpChallenge(
            scheme = "Basic",
            realm = Some(basicAuthRealm),
            params = Map("charset" -> "UTF-8")
          )
        }

        ctrl.shutdown()
      }

    "return the kafka cluster info when secured with OpenID Connect" in
      withEmbeddedOpenIdConnectServerAndToken() {
        case (_, _, _, cfg, token) =>
          withRunningKafkaOnFoundPort(embeddedKafkaConfig) { implicit kcfg =>
            implicit val wsCfg =
              appTestConfig(kafkaPort = kcfg.kafkaPort, openIdCfg = Option(cfg))

            val (sdcStream, testRoutes) = TestServerRoutes.wsProxyRoutes
            val ctrl                    = sdcStream.run()

            Get("/kafka/cluster/info") ~> addCredentials(
              token.bearerToken
            ) ~> Route.seal(testRoutes) ~> check {
              status mustBe OK
              responseEntity.contentType mustBe ContentTypes.`application/json`

              val ci = parse(responseAs[String])
                .map(_.as[Seq[BrokerInfo]])
                .flatMap(identity)
                .right
                .value

              ci must have size 1
              ci.headOption.value mustBe BrokerInfo(
                id = 0,
                host = "localhost",
                port = kcfg.kafkaPort,
                rack = None
              )
            }

            ctrl.shutdown()
          }
      }

    "return 401 when accessing kafka cluster info with invalid bearer token" in
      withEmbeddedOpenIdConnectServerAndClient() {
        case (_, _, _, cfg) =>
          withRunningKafkaOnFoundPort(embeddedKafkaConfig) { implicit kcfg =>
            implicit val wsCfg =
              appTestConfig(kafkaPort = kcfg.kafkaPort, openIdCfg = Option(cfg))

            val (sdcStream, testRoutes) = TestServerRoutes.wsProxyRoutes
            val ctrl                    = sdcStream.run()

            Get("/kafka/cluster/info") ~> addCredentials(
              OAuth2BearerToken("invalid-token")
            ) ~> Route.seal(testRoutes) ~> check {
              status mustBe Unauthorized
            }

            ctrl.shutdown()
          }
      }
  }

}
