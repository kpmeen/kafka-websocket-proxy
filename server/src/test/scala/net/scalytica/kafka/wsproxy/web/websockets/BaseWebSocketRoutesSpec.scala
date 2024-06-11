package net.scalytica.kafka.wsproxy.web

import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.ContentTypes
import org.apache.pekko.http.scaladsl.model.StatusCodes._
import org.apache.pekko.http.scaladsl.model.headers.Upgrade
import org.apache.pekko.http.scaladsl.model.ws.{Message, WebSocketRequest}
import org.apache.pekko.stream.scaladsl.{Flow, Keep, Sink, Source}
import net.scalytica.test._
import org.apache.pekko.http.scaladsl.testkit.WSProbe
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Minutes, Span}
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{Assertion, BeforeAndAfterAll, OptionValues, Suite}

import scala.concurrent.duration._

trait BaseWebSocketRoutesSpec
    extends AnyWordSpecLike
    with TestServerRoutes
    with WsProxySpec
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
    implicit val wsClient: WSProbe = ctx.producerProbe
    val uri = buildProducerUri(
      producerId = None,
      instanceId = None,
      topicName = ctx.topicName
    )

    assertProducerWS(wsRouteFromProducerContext, uri) {
      isWebSocketUpgrade mustBe false
      status mustBe BadRequest
      responseAs[String] must include("clientId")
      contentType mustBe ContentTypes.`application/json`
    }
  }

  /**
   * Assert cases for when there is no instanceId provided in the URI
   * parameters.
   *
   * @param useSession
   *   If true, will enable producer sessions
   * @param exactlyOnce
   *   if {{{Some(true)}}} will enable exactly once semantics (aka
   *   transactional), otherwise it will be disabled. If this argument has a
   *   true value, the {{{useSession}}} argument _must_ be true also.
   * @param ctx
   *   The [[ProducerContext]] to use
   * @return
   *   [[Assertion]]
   */
  final protected def assertNoInstanceId(
      useSession: Boolean,
      exactlyOnce: Option[Boolean] = None
  )(implicit ctx: ProducerContext): Assertion = {
    implicit val wsClient: WSProbe = ctx.producerProbe
    val uri = buildProducerUri(
      producerId = Some(producerId("producer", topicCounter)),
      instanceId = None,
      topicName = ctx.topicName,
      transactional = exactlyOnce
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
    implicit val wsClient: WSProbe = ctx.producerProbe
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
