package net.scalytica.kafka.wsproxy.web

import akka.actor.ActorSystem
import akka.actor.typed.scaladsl.adapter._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.kafka.scaladsl.Consumer
import akka.stream.Materializer
import akka.stream.scaladsl.RunnableGraph
import net.scalytica.kafka.wsproxy.auth.OpenIdClient
import net.scalytica.kafka.wsproxy.config.Configuration.AppCfg
import net.scalytica.kafka.wsproxy.jmx.JmxManager
import net.scalytica.kafka.wsproxy.models._
import net.scalytica.kafka.wsproxy.session.SessionHandlerImplicits._
import net.scalytica.kafka.wsproxy.session.SessionHandlerRef
import net.scalytica.kafka.wsproxy.web.websockets.{
  InboundWebSocket,
  OutboundWebSocket
}

import scala.concurrent.ExecutionContext

trait ServerRoutes
    extends BaseRoutes
    with OutboundWebSocket
    with InboundWebSocket
    with StatusRoutes
    with SchemaRoutes
    with WebSocketRoutes
    with AdminRoutes { self =>

  /**
   * @param cfg
   *   Implicitly provided [[AppCfg]]
   * @param sessionHandlerRef
   *   Implicitly provided [[SessionHandlerRef]] to use
   * @param maybeOpenIdClient
   *   Implicitly provided Option that contains an [[OpenIdClient]] if OIDC is
   *   enabled.
   * @param sys
   *   Implicitly provided [[ActorSystem]]
   * @param mat
   *   Implicitly provided [[Materializer]]
   * @param ctx
   *   Implicitly provided [[ExecutionContext]]
   * @param jmx
   *   Implicitly provided optional [[JmxManager]]
   * @return
   *   a tuple containing a [[RunnableGraph]] and the [[Route]] definition
   */
  def wsProxyRoutes(
      implicit cfg: AppCfg,
      sessionHandlerRef: SessionHandlerRef,
      maybeOpenIdClient: Option[OpenIdClient],
      sys: ActorSystem,
      mat: Materializer,
      ctx: ExecutionContext,
      jmx: Option[JmxManager] = None
  ): (RunnableGraph[Consumer.Control], Route) = {
    sessionHandler = sessionHandlerRef // TODO: This mutation hurts my pride
    implicit val sh = sessionHandler.shRef

    // Wait for session state to be restored before continuing
    try {
      sh.awaitSessionRestoration()(ctx, sys.toTyped.scheduler)
    } catch {
      case t: Throwable =>
        log
          .error("Unable to restore session state. Terminating application.", t)
        System.exit(1)
    }

    (sessionHandler.stream, routesWith(inboundWebSocket, outboundWebSocket))
  }

  /**
   * @param inbound
   *   function defining the [[Route]] for the producer socket
   * @param outbound
   *   function defining the [[Route]] for the consumer socket
   * @param cfg
   *   Implicitly provided [[AppCfg]]
   * @return
   *   a new [[Route]]
   */
  def routesWith(
      inbound: InSocketArgs => Route,
      outbound: OutSocketArgs => Route
  )(implicit cfg: AppCfg, maybeOpenIdClient: Option[OpenIdClient]): Route = {
    handleExceptions(wsExceptionHandler) {
      schemaRoutes ~
        websocketRoutes(inbound, outbound) ~
        statusRoutes
    }
  }

}
