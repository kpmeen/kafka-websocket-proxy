package net.scalytica.kafka.wsproxy.web

import akka.http.scaladsl.model.{ContentTypes, HttpResponse, StatusCodes}
import akka.http.scaladsl.server.{Directives, Route}
import net.scalytica.test.{TestAdHocRoute, WsProxyKafkaSpec}
import org.scalatest.wordspec.AnyWordSpec

// scalastyle:off magic.number
class QueryParamsParsersSpec
    extends AnyWordSpec
    with WsProxyKafkaSpec
    with TestAdHocRoute
    with Directives
    with QueryParamParsers {

  val Ok         = HttpResponse()
  val completeOk = complete(Ok)

  lazy val transAppCfg = appTestConfig(
    kafkaPort = 9092,
    useProducerSessions = true,
    useExactlyOnce = true
  )
  lazy val transNoSessAppCfg = appTestConfig(
    kafkaPort = 9092,
    useExactlyOnce = true
  )

  lazy val sessNoTransAppCfg = appTestConfig(
    kafkaPort = 9092,
    useProducerSessions = true
  )

  def echoComplete[T]: T => Route = { x => complete(x.toString) }

  "Parsing query parameters" when {

    "expecting params for an inbound WebSocket" should {
      "succeed when all parameters have valid values" in {
        Get(
          "/?clientId=foobar&" +
            "topic=test-topic&" +
            "socketPayload=json&" +
            "instanceId=test-instance&" +
            "keyType=string&" +
            "valType=string"
        ) ~> routeWithExceptionHandler(
          inParams(defaultTestAppCfg)(echoComplete)
        ) ~> check {
          status mustBe StatusCodes.OK
        }
      }

      "succeed with only required parameters given" in {
        Get(
          "/?clientId=foobar&" +
            "socketPayload=json&" +
            "topic=test-topic"
        ) ~> routeWithExceptionHandler(
          inParams(defaultTestAppCfg)(echoComplete)
        ) ~> check {
          status mustBe StatusCodes.OK
        }
      }

      "reject when an argument has an invalid value" in {
        Get(
          "/?clientId=foobar&" +
            "socketPayload=protobuf&" +
            "topic=test-topic"
        ) ~> routeWithExceptionHandler(
          inParams(defaultTestAppCfg)(echoComplete)
        ) ~> check {
          status mustBe StatusCodes.BadRequest
          contentType mustBe ContentTypes.`application/json`
          responseAs[String] must include(
            "'protobuf' is not a valid socket payload"
          )
        }
      }

      "reject when a required parameter is missing" in {
        Get(
          "/?clientId=foobar&" +
            "socketPayload=json"
        ) ~> routeWithExceptionHandler(
          inParams(defaultTestAppCfg)(echoComplete)
        ) ~> check {
          status mustBe StatusCodes.BadRequest
          contentType mustBe ContentTypes.`application/json`
          responseAs[String] must include(
            "Request param 'topic' is missing."
          )
        }
      }

      "reject when instanceId is missing and producer sessions are enabled" in {
        Get(
          "/?clientId=foobar&" +
            "socketPayload=json&" +
            "topic=test-topic"
        ) ~> routeWithExceptionHandler(
          inParams(sessNoTransAppCfg)(echoComplete)
        ) ~> check {
          status mustBe StatusCodes.BadRequest
          contentType mustBe ContentTypes.`application/json`
          responseAs[String] must include(
            "Request param 'instanceId' is required when producer " +
              "sessions are enabled on the proxy server."
          )
        }
      }

      "succeed transactional when transactions and sessions are enabled" in {
        Get(
          "/?clientId=foobar&" +
            "socketPayload=json&" +
            "topic=test-topic&" +
            "instanceId=test-instance&" +
            "transactional=true"
        ) ~> routeWithExceptionHandler(
          inParams(transAppCfg)(echoComplete)
        ) ~> check {
          status mustBe StatusCodes.OK
        }
      }

      "reject transactional when not enabled" in {
        Get(
          "/?clientId=foobar&" +
            "socketPayload=json&" +
            "topic=test-topic&" +
            "instanceId=test-instance&" +
            "transactional=true"
        ) ~> routeWithExceptionHandler(
          inParams(defaultTestAppCfg)(echoComplete)
        ) ~> check {
          status mustBe StatusCodes.BadRequest
          contentType mustBe ContentTypes.`application/json`
          responseAs[String] must include(
            "Unable to provide transactional producer. Server is not " +
              "configured to allow producer transactions."
          )
        }
      }

      "reject transactional when producer sessions are not enabled" in {
        Get(
          "/?clientId=foobar&" +
            "socketPayload=json&" +
            "topic=test-topic&" +
            "instanceId=test-instance&" +
            "transactional=true"
        ) ~> routeWithExceptionHandler(
          inParams(transNoSessAppCfg)(echoComplete)
        ) ~> check {
          status mustBe StatusCodes.BadRequest
          contentType mustBe ContentTypes.`application/json`
          responseAs[String] must include(
            "Unable to provide transactional producer. Server is not " +
              "configured to allow producer transactions."
          )
        }
      }

      "reject transactional when no instanceId is provided" in {
        Get(
          "/?clientId=foobar&" +
            "socketPayload=json&" +
            "topic=test-topic&" +
            "transactional=true"
        ) ~> routeWithExceptionHandler(
          inParams(transAppCfg)(echoComplete)
        ) ~> check {
          status mustBe StatusCodes.BadRequest
          contentType mustBe ContentTypes.`application/json`
          responseAs[String] must include(
            "Request param 'instanceId' is required when using transactional."
          )
        }
      }
    }

    "expecting params for an outbound WebSocket" should {
      "succeed when all parameters have valid values" in {
        Get(
          "/?clientId=foobar&" +
            "groupId=test-group&" +
            "topic=test-topic&" +
            "socketPayload=json&" +
            "keyType=string&" +
            "valType=string&" +
            "offsetResetStrategy=latest&" +
            "isolationLevel=read_uncommitted&" +
            "rate=100&" +
            "batchSize=100&" +
            "autoCommit=false"
        ) ~> routeWithExceptionHandler(outParams(echoComplete)) ~> check {
          status mustBe StatusCodes.OK
        }
      }

      "succeed with only required parameters given" in {
        Get(
          "/?clientId=foobar&" +
            "topic=test-topic"
        ) ~> routeWithExceptionHandler(outParams(echoComplete)) ~> check {
          status mustBe StatusCodes.OK
        }
      }

      "reject when an argument has an invalid value" in {
        Get(
          "/?clientId=foobar&" +
            "groupId=test-group&" +
            "topic=test-topic&" +
            "socketPayload=json&" +
            "keyType=string&" +
            "valType=string&" +
            "offsetResetStrategy=latest&" +
            "isolationLevel=uncommitted&" +
            "rate=100&" +
            "batchSize=100&" +
            "autoCommit=false"
        ) ~> routeWithExceptionHandler(outParams(echoComplete)) ~> check {
          status mustBe StatusCodes.BadRequest
          contentType mustBe ContentTypes.`application/json`
          responseAs[String] must include(
            "Read isolation 'uncommitted' is not a valid value. Please use" +
              " one of 'read_committed' or 'read_uncommitted'."
          )
        }
      }

      "reject when a required parameter is missing" in {
        Get(
          "/?groupId=test-group&" +
            "topic=test-topic&" +
            "socketPayload=json&" +
            "keyType=string&" +
            "valType=string&" +
            "offsetResetStrategy=latest&" +
            "isolationLevel=read_uncommitted&" +
            "rate=100&" +
            "batchSize=100&" +
            "autoCommit=false"
        ) ~> routeWithExceptionHandler(outParams(echoComplete)) ~> check {
          status mustBe StatusCodes.BadRequest
          contentType mustBe ContentTypes.`application/json`
          responseAs[String] must include(
            "Request param 'clientId' is missing."
          )
        }
      }
    }
  }

}
