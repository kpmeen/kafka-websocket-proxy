package net.scalytica.kafka.wsproxy.web

import akka.http.scaladsl.model.{ContentTypes, HttpResponse, StatusCodes}
import akka.http.scaladsl.server.{Directives, Route}
import net.scalytica.test.{TestAdHocRoute, WsProxyKafkaSpec}
import org.scalatest.wordspec.AnyWordSpec

class QueryParamsParsersSpec
    extends AnyWordSpec
    with WsProxyKafkaSpec
    with TestAdHocRoute
    with Directives
    with QueryParamParsers {

  val Ok         = HttpResponse()
  val completeOk = complete(Ok)

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
        ) ~> routeWithExceptionHandler(inParams(echoComplete)) ~> check {
          status mustBe StatusCodes.OK
        }
      }

      "succeed with only required parameters given" in {
        Get(
          "/?clientId=foobar&" +
            "socketPayload=json&" +
            "topic=test-topic"
        ) ~> routeWithExceptionHandler(inParams(echoComplete)) ~> check {
          status mustBe StatusCodes.OK
        }
      }

      "fail when an argument has an invalid value" in {
        Get(
          "/?clientId=foobar&" +
            "socketPayload=protobuf&" +
            "topic=test-topic"
        ) ~> routeWithExceptionHandler(inParams(echoComplete)) ~> check {
          status mustBe StatusCodes.BadRequest
          contentType mustBe ContentTypes.`application/json`
          responseAs[String] must include(
            "'protobuf' is not a valid socket payload"
          )
        }
      }

      "fail when a required parameter is missing" in {
        Get(
          "/?clientId=foobar&" +
            "socketPayload=json"
        ) ~> routeWithExceptionHandler(inParams(echoComplete)) ~> check {
          status mustBe StatusCodes.BadRequest
          contentType mustBe ContentTypes.`application/json`
          responseAs[String] must include(
            "Request param 'topic' is missing."
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

      "fail when an argument has an invalid value" in {
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

      "fail when a required parameter is missing" in {
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
