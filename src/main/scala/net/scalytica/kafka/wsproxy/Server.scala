package net.scalytica.kafka.wsproxy

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.stream.ActorMaterializer
import com.typesafe.scalalogging.Logger
import net.scalytica.kafka.wsproxy.Configuration.AppConfig

import scala.concurrent.ExecutionContext
import scala.io.StdIn

object Server
    extends App
    with QueryParamParsers
    with OutboundWebSocket
    with InboundWebSocket {

  private[this] val logger = Logger(this.getClass)

  implicit private[this] val cfg: AppConfig         = Configuration.load()
  implicit private[this] val sys: ActorSystem       = ActorSystem()
  implicit private[this] val mat: ActorMaterializer = ActorMaterializer()
  implicit private[this] val ctx: ExecutionContext  = sys.dispatcher

  private[this] val port = 8080

  implicit private[this] def errorHandler: ExceptionHandler = ExceptionHandler {
    case t: Throwable =>
      extractUri { uri =>
        logger.warn(s"Request to $uri could not be handled normally", t)
        complete(HttpResponse(InternalServerError, entity = t.getMessage))
      }
  }

  /** Endpoint route(s) providing access to the Kafka WebSocket */
  private[this] val route =
    pathPrefix("socket") {
      path("in") {
        inParams(inboundWebSocket)
      } ~
        path("out") {
          outParams(outboundWebSocket)
        }
    }

  /** Bind to network interface and port, starting the server */
  private[this] val bindingFuture = Http().bindAndHandle(
    handler = route,
    interface = "localhost",
    port = port
  )

  // scalastyle:off
  println(
    s"""Server online at http://localhost:$port/
       |Press RETURN to stop...""".stripMargin
  )
  StdIn.readLine()
  // scalastyle:on

  /** Unbind from the network interface and port, shutting down the server. */
  bindingFuture.flatMap(_.unbind()).onComplete(_ => sys.terminate())
}
