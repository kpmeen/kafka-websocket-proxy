package net.scalytica.kafka.wsproxy.auth

import akka.NotUsed
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.stream.scaladsl.Flow
import akka.stream.stage._
import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import net.scalytica.kafka.wsproxy.auth.JwtValidationTickerFlow._
import net.scalytica.kafka.wsproxy.config.Configuration.AppCfg
import net.scalytica.kafka.wsproxy.errors.OpenIdConnectError
import net.scalytica.kafka.wsproxy.logging.WithProxyLogger
import net.scalytica.kafka.wsproxy.models.WsIdentifier

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

/**
 * The JwtValidationTickerFlow is intended to be plugged into an akka-http
 * connection that needs to be kept open for longer durations. This could be
 * WebSockets, Server Sent Events connection or a HTTP long-poll connection.
 *
 * This is a pass-through implementation that will not manipulate the messages
 * in any way.
 *
 * Internally, there is a scheduled ticker that will fire at an interval that is
 * configurable through a {{revalidationInterval}}. When the scheduler is
 * triggered, the flow will make an async call to validate the JWT with the
 * configured OpenID Connect server.
 *
 * How validation is implemented:
 *
 *   1. Token will be valid or invalid according to the response from the OpenID
 *      Connect server.
 *
 * 2. If the OpenID Connect server is unavailable, the token is considered valid
 * until the OIDC server is reachable again. This is to avoid any unnecessary
 * service disruptions for connecting clients.
 *
 * @param clientId
 *   The [[WsIdentifier]] used by the websocket client to establish the
 *   connection
 * @param bearerToken
 *   The JWT token
 * @param appCfg
 *   Implicitly provided [[AppCfg]]
 * @param maybeOpenIdClient
 *   Implicitly provided Option that contains an [[OpenIdClient]] if OIDC is
 *   enabled.
 * @param ec
 *   Implicitly provided [[ExecutionContext]]
 * @tparam M
 *   The type of inbound and outbound messages
 */
private[auth] class JwtValidationTickerFlow[M](
    clientId: WsIdentifier,
    bearerToken: OAuth2BearerToken
)(
    implicit appCfg: AppCfg,
    maybeOpenIdClient: Option[OpenIdClient],
    ec: ExecutionContext
) extends GraphStage[FlowShape[M, M]]
    with WithProxyLogger {

  val in: Inlet[M]           = Inlet("JwtValidationTickerIn")
  val out: Outlet[M]         = Outlet("JwtValidationTickerOut")
  val shape: FlowShape[M, M] = FlowShape(in, out)

  // scalastyle:off method.length
  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = {
    new TimerGraphStageLogic(shape) {
      private[this] val interval = appCfg.server.openidConnect
        .map(_.revalidationInterval)
        .getOrElse(10 minutes)

      private[this] val timerKey = clientId.value

      @volatile private[this] var previousWasError = false
      @volatile private[this] var errorCounter     = 0
      private[this] val maxErrors =
        appCfg.server.openidConnect.map(_.revalidationErrorsLimit).getOrElse(-1)

      private[this] def validateJwtToken(
          client: OpenIdClient
      ): Future[ValidationResult] = {
        client.validateToken[ValidationResult](bearerToken)(
          valid = _ => Valid,
          invalid = ae => Invalid(ae),
          errorHandler = ex => TransientError(ex)
        )
      }

      private[this] def isLimitDisabled: Boolean   = maxErrors == -1
      private[this] def errorLimitReached: Boolean = errorCounter >= maxErrors

      private[this] def updateErrorState(): ErrorState = {
        previousWasError = true
        errorCounter = errorCounter + 1
        log.trace(s"Num transient errors: $errorCounter")
        log.trace(s"Previous was transient error: $previousWasError")

        if (!errorLimitReached || isLimitDisabled) {
          log.warn("Keeping socket open until JWT can be validated.")
          Ok
        } else {
          log.warn("Error limit for JWT validation has been reached.")
          Ko
        }
      }

      private[this] def resetErrorCounter(): Unit = {
        previousWasError = false
        errorCounter = 0
      }

      override def preStart(): Unit = {
        log.info(s"Starting JwtValidationTickerFlow for client $timerKey")
        if (appCfg.server.isOpenIdConnectEnabled) {
          scheduleAtFixedRate(timerKey, interval, interval)
        } else {
          log.info("OIDC isn't configured. Skipping JWT validation ticker.")
        }
      }

      override def postStop(): Unit = {
        log.info(s"Stopped JwtValidationTickerFlow for client $timerKey")
      }

      override def onTimer(timerKey: Any): Unit = {
        maybeOpenIdClient.foreach { client =>
          val callback = getAsyncCallback[Unit] { _ => () }
          callback.invoke {
            log.debug(s"Triggered token validation for client $timerKey.")
            validateJwtToken(client).foreach {
              case Valid =>
                log.trace(s"JWT for client $timerKey is valid.")
                resetErrorCounter()

              case TransientError(e) =>
                updateErrorState() match {
                  case Ok => ()
                  case Ko =>
                    cancelTimer(timerKey)
                    failStage(OpenIdConnectError(e.getMessage))
                }

              case Invalid(e) =>
                log.info(s"JWT for client $timerKey was invalidated")
                cancelTimer(timerKey)
                failStage(e)
            }
          }
        }
      }

      setHandlers(
        in = in,
        out = out,
        handler = new InHandler with OutHandler {
          override def onPush(): Unit = push(out, grab(in))
          override def onPull(): Unit = pull(in)
        }
      )
    }
  }
  // scalastyle:on method.length

}

object JwtValidationTickerFlow {

  sealed private trait ValidationResult
  private case object Valid                        extends ValidationResult
  private case class Invalid(ex: Throwable)        extends ValidationResult
  private case class TransientError(ex: Throwable) extends ValidationResult

  sealed private trait ErrorState
  private case object Ok extends ErrorState
  private case object Ko extends ErrorState

  def flow[M](
      clientId: WsIdentifier,
      maybeBearerToken: Option[OAuth2BearerToken]
  )(
      implicit appCfg: AppCfg,
      maybeOpenIdClient: Option[OpenIdClient],
      ec: ExecutionContext
  ): Flow[M, M, NotUsed] = {
    maybeBearerToken
      .map(t => new JwtValidationTickerFlow[M](clientId, t))
      .map(Flow.fromGraph[M, M, NotUsed])
      .getOrElse(Flow[M])
  }

}
