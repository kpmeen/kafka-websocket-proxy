package net.scalytica.kafka.wsproxy

import akka.http.scaladsl.testkit.{ScalatestRouteTest, WSProbe}
import akka.http.scaladsl.server._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.ws.TextMessage
import akka.stream.scaladsl.Sink
import io.circe._
import io.circe.parser._
import net.scalytica.kafka.wsproxy.models.{
  ConsumerKeyValueRecord,
  Formats,
  ProducerKeyValueRecord,
  WsProducerResult
}
import net.scalytica.kafka.wsproxy.models.ValueDetails.{
  InValueDetails,
  OutValueDetails
}
import net.scalytica.kafka.wsproxy.codecs.Decoders._
import net.scalytica.test._
import org.scalatest.{EitherValues, MustMatchers, WordSpec}

import scala.concurrent.duration._

class ServerRoutesSpec
    extends WordSpec
    with MustMatchers
    with ScalatestRouteTest
    with EitherValues {

  implicit val cfg = Configuration.loadFrom(
    "kafka.websocket.proxy.server.port"                         -> 8078,
    "kafka.websocket.proxy.server.kafka-bootstrap-urls"         -> """["localhost:29092"]""",
    "kafka.websocket.proxy.consumer.default-rate-limit"         -> 0,
    "kafka.websocket.proxy.consumer.default-batch-size"         -> 0,
    "kafka.websocket.proxy.commit-handler.max-stack-size"       -> 200,
    "kafka.websocket.proxy.commit-handler.auto-commit-enabled"  -> false,
    "kafka.websocket.proxy.commit-handler.auto-commit-interval" -> 1.second,
    "kafka.websocket.proxy.commit-handler.auto-commit-max-age"  -> 20.seconds
  )

  case object TestRoutes extends ServerRoutes

  import TestRoutes.{serverErrorHandler, serverRejectionHandler}

  private[this] def newProducerKeyValueRecord() = {
    """{
      |  "key": {
      |    "value": "foo",
      |    "format": "string"
      |  },
      |  "value": {
      |    "value": "bar",
      |    "format": "string"
      |  }
      |}""".stripMargin
  }

  def produce(
      inPath: String,
      routes: Route
  )(implicit wsClient: WSProbe): Unit = {
    WS(inPath, wsClient.flow) ~> routes ~> check {
      isWebSocketUpgrade mustBe true

      wsClient.sendMessage(newProducerKeyValueRecord())
      wsClient.expectWsProducerResult("foobar")

      wsClient.sendCompletion()
      wsClient.expectCompletion()
    }
  }

  "The server routes" should {
    "return a 404 NotFound when requesting an invalid resource" in {
      val routes = Route.seal(TestRoutes.routes)

      Get() ~> routes ~> check {
        status mustBe NotFound
        responseAs[String] mustBe "This is not the page you are looking for."
      }
    }

    "set up a WebSocket connection for producing messages" in {
      val routes            = Route.seal(TestRoutes.routes)
      implicit val wsClient = WSProbe()

      produce("/socket/in?topic=foobar&keyType=string&valType=string", routes)
    }

    "set up a WebSocket connection for consuming messages" in {
      val routes                   = Route.seal(TestRoutes.routes)
      val producerProbe            = WSProbe()
      implicit val wsConsumerProbe = WSProbe()

      produce("/socket/in?topic=foobar&keyType=string&valType=string", routes)(
        producerProbe
      )

      val outPath = "/socket/out?" +
        "clientId=test" +
        "&groupId=test-group" +
        "&topic=foobar" +
        "&keyType=string" +
        "&valType=string" +
        "&autoCommit=false"

      val record =
        """{
          |  "wsProxyMessageId": "foobar-2-12-1554402266846",
          |  "partition": 2,
          |  "offset": 12L,
          |  "key": {
          |    "value": "foo",
          |    "format": "string"
          |  },
          |  "value": {
          |    "value": "bar",
          |    "format": "string"
          |  }
          |}""".stripMargin

      WS(outPath, wsConsumerProbe.flow) ~> routes ~> check {
        isWebSocketUpgrade mustBe true

        wsConsumerProbe.expectWsConsumerKeyValueResult[String, String](
          expectedTopic = "foobar",
          expectedKey = "foo",
          expectedValue = "bar"
        )

      }
    }
  }

}
