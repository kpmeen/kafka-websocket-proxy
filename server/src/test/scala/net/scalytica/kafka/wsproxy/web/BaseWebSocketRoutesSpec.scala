package net.scalytica.kafka.wsproxy.web

import akka.http.scaladsl.Http
import akka.http.scaladsl.model.ContentTypes
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.headers.Upgrade
import akka.http.scaladsl.model.ws.{Message, WebSocketRequest}
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import net.scalytica.test._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Minutes, Span}
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{Assertion, BeforeAndAfterAll, OptionValues, Suite}

import scala.concurrent.duration._

trait BaseWebSocketRoutesSpec
    extends AnyWordSpecLike
    with TestServerRoutes
    with WsProxyConsumerKafkaSpec
    with MockOpenIdServer
    with EmbeddedHttpServer
    with OptionValues
    with ScalaFutures
    with BeforeAndAfterAll
    with TestDataGenerators { self: Suite =>

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(2, Minutes))

  final protected def assertWSRequest(
      host: String,
      port: Int,
      uri: String,
      expectFailure: Boolean = false,
      numExpMsgs: Int = 0
  )(
      initialDelay: FiniteDuration = 0 seconds,
      openConnectionDuration: FiniteDuration = 5 seconds
  ): Assertion = {
    val ioIn   = Source.maybe[Message]
    val ioOut  = Sink.seq[Message]
    val ioFlow = Flow.fromSinkAndSourceMat(ioOut, ioIn)(Keep.both)

    Thread.sleep(initialDelay.toMillis)

    val (upgradeRes, (closed, promise)) = Http().singleWebSocketRequest(
      request = WebSocketRequest(s"ws://$host:$port$uri"),
      clientFlow = ioFlow
    )

    val connected = upgradeRes.futureValue

    if (expectFailure) {
      connected.response.status must not be SwitchingProtocols
      connected.response.header[Upgrade] mustBe empty
    } else {
      connected.response.status mustBe SwitchingProtocols
      connected.response.header[Upgrade].exists(_.hasWebSocket) mustBe true
    }

    Thread.sleep(openConnectionDuration.toMillis)

    promise.success(None)

    val result = closed.futureValue
    result.size mustBe numExpMsgs
  }

  final protected def assertRejectMissingProducerId()(
      implicit ctx: ProducerContext
  ): Assertion = {
    implicit val wsClient = ctx.producerProbe
    val uri = buildProducerUri(
      producerId = None,
      instanceId = None,
      topicName = Some(ctx.topicName)
    )

    assertProducerWS(wsRouteFromProducerContext, uri) {
      isWebSocketUpgrade mustBe false
      status mustBe BadRequest
      responseAs[String] must include("clientId")
      contentType mustBe ContentTypes.`application/json`
    }
  }

  final protected def assertRejectMissingInstanceId(
      useSession: Boolean
  )(implicit ctx: ProducerContext): Assertion = {
    implicit val wsClient = ctx.producerProbe
    val uri = buildProducerUri(
      producerId = Some(producerId("producer", topicCounter)),
      instanceId = None,
      topicName = Some(ctx.topicName)
    )

    assertProducerWS(wsRouteFromProducerContext, uri) {
      if (useSession) {
        isWebSocketUpgrade mustBe false
        status mustBe BadRequest
        responseAs[String] must include("instanceId")
      } else {
        isWebSocketUpgrade mustBe true
        status mustBe SwitchingProtocols
      }
    }
  }

  final protected def assertRejectMissingTopicName()(
      implicit ctx: ProducerContext
  ): Assertion = {
    implicit val wsClient = ctx.producerProbe
    val uri = buildProducerUri(
      producerId = Some(producerId("producer", topicCounter)),
      instanceId = None,
      topicName = None
    )

    assertProducerWS(wsRouteFromProducerContext, uri) {
      isWebSocketUpgrade mustBe false
      status mustBe BadRequest
      responseAs[String] must include("topic")
      contentType mustBe ContentTypes.`application/json`
    }
  }
}
