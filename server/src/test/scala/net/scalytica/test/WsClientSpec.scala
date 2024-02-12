package net.scalytica.test

import org.apache.pekko.http.scaladsl.model.headers.{
  Authorization,
  BasicHttpCredentials,
  HttpCredentials
}
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.testkit.{
  RouteTestTimeout,
  ScalatestRouteTest,
  WSProbe
}
import org.apache.pekko.testkit.TestDuration
import net.scalytica.kafka.wsproxy.auth.OpenIdClient
import net.scalytica.kafka.wsproxy.codecs.ProtocolSerdes
import net.scalytica.kafka.wsproxy.config.Configuration.AppCfg
import net.scalytica.kafka.wsproxy.config.ReadableDynamicConfigHandlerRef
import net.scalytica.kafka.wsproxy.session.SessionHandlerRef
import net.scalytica.kafka.wsproxy.web.Headers.XKafkaAuthHeader
import org.scalatest.Suite
import org.scalatest.matchers.must.Matchers

import scala.concurrent.duration._

trait WsClientSpec
    extends ScalatestRouteTest
    with TestServerRoutes
    with Matchers
    with ProtocolSerdes { self: Suite =>

  implicit private[this] val routeTestTimeout: RouteTestTimeout =
    RouteTestTimeout((20 seconds).dilated)

  protected def wsRouteFrom(
      implicit cfg: AppCfg,
      shRef: SessionHandlerRef,
      optReadCfgRef: Option[ReadableDynamicConfigHandlerRef],
      optOidc: Option[OpenIdClient]
  ): Route = wsProxyRoutes

  /** Verify the server routes using an unsecured Kafka cluster */
  private[this] def defaultRouteCheck[T](
      uri: String,
      routes: Route,
      creds: Option[HttpCredentials] = None
  )(
      body: => T
  )(
      implicit probe: WSProbe
  ): T = {
    creds match {
      case None => WS(uri, probe.flow) ~> routes ~> check(body)
      case Some(c) =>
        val authHeader = addHeader(Authorization(c))
        WS(uri, probe.flow) ~> authHeader ~> routes ~> check(body)
    }
  }

  /** Verify the server routes using a secured Kafka cluster */
  private[this] def secureKafkaRouteCheck[T](
      uri: String,
      routes: Route,
      kafkaCreds: XKafkaAuthHeader,
      creds: Option[HttpCredentials] = None
  )(
      body: => T
  )(
      implicit wsClient: WSProbe
  ): T = {
    val headers = creds match {
      case Some(c) => addHeaders(Authorization(c), kafkaCreds)
      case None    => addHeader(kafkaCreds)
    }
    WS(uri, wsClient.flow) ~> headers ~> routes ~> check(body)
  }

  /** Set the X-Kafka-Auth header */
  def addKafkaCreds(creds: BasicHttpCredentials): RequestTransformer = {
    val kaHeader = XKafkaAuthHeader(creds)
    addHeader(kaHeader)
  }

  /** Check that the websocket behaves */
  def inspectWebSocket[T](
      uri: String,
      routes: Route,
      kafkaCreds: Option[BasicHttpCredentials] = None,
      creds: Option[HttpCredentials] = None
  )(body: => T)(implicit wsClient: WSProbe): T = {
    val u = creds.map(c => uri + s"&access_token=${c.token}").getOrElse(uri)

    kafkaCreds
      .map(c => secureKafkaRouteCheck(u, routes, XKafkaAuthHeader(c))(body))
      .getOrElse(defaultRouteCheck(u, routes)(body))
  }

}
