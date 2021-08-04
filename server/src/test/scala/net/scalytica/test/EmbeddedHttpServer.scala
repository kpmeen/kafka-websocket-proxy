package net.scalytica.test

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{ExceptionHandler, Route}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

trait EmbeddedHttpServer {

  val shutdownDeadline: FiniteDuration = 20 seconds

  implicit private[this] def exceptionHandler: ExceptionHandler =
    ExceptionHandler { case t: Throwable =>
      complete(
        HttpResponse(
          status = StatusCodes.InternalServerError,
          entity = t.getMessage
        )
      )
    }

  def withHttpServerForRoute[T](
      host: String = "localhost",
      port: Int = availablePort
  )(routes: (String, Int) => Route)(block: (String, Int) => T)(
      implicit sys: ActorSystem,
      ec: ExecutionContext
  ): T = withEmbeddedServer(host, port, routes(host, port))(block)

  def withEmbeddedServer[T](
      host: String = "localhost",
      port: Int = availablePort,
      routes: Route,
      completionWaitDuration: Option[FiniteDuration] = None
  )(block: (String, Int) => T)(
      implicit sys: ActorSystem,
      ec: ExecutionContext
  ): T = {
    val server = Http().newServerAt(host, port).bindFlow(Route.seal(routes))
    try {
      val res = block(host, port)
      completionWaitDuration match {
        case Some(waitDuration) => Thread.sleep(waitDuration.toMillis)
        case None               => ()
      }
      res
    } finally {
      val _ = server.flatMap(_.terminate(shutdownDeadline))
    }
  }
}
