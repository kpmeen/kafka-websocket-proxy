package net.scalytica.kafka.wsproxy

import akka.actor.ActorSystem
import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{ExceptionHandler, RejectionHandler, Route}
import akka.stream.ActorMaterializer
import com.typesafe.scalalogging.Logger
import net.scalytica.kafka.wsproxy.Configuration.AppCfg
import net.scalytica.kafka.wsproxy.avro.SchemaTypes.{
  AvroCommit,
  AvroConsumerRecord,
  AvroProducerRecord,
  AvroProducerResult
}
import net.scalytica.kafka.wsproxy.models.{InSocketArgs, OutSocketArgs}

import scala.concurrent.ExecutionContext

trait ServerRoutes
    extends QueryParamParsers
    with OutboundWebSocket
    with InboundWebSocket {

  private[this] val logger = Logger(this.getClass)

  private[this] def rejectAndComplete(m: => ToResponseMarshallable) = {
    extractRequest { request =>
      logger.warn(
        s"Request ${request.method.value} ${request.uri.toString} failed"
      )
      extractMaterializer { implicit mat ⇒
        request.discardEntityBytes()
        complete(m)
      }
    }
  }

  implicit def serverErrorHandler: ExceptionHandler = ExceptionHandler {
    case t: Throwable =>
      extractUri { uri =>
        logger.warn(s"Request to $uri could not be handled normally", t)
        complete(HttpResponse(InternalServerError, entity = t.getMessage))
      }
  }

  implicit def serverRejectionHandler: RejectionHandler = {
    RejectionHandler
      .newBuilder()
      .handleNotFound {
        rejectAndComplete(
          (NotFound, "This is not the page you are looking for.")
        )
      }
      .result()
      .withFallback(RejectionHandler.default)
  }

  def routes(
      implicit
      cfg: AppCfg,
      sys: ActorSystem,
      mat: ActorMaterializer,
      ctx: ExecutionContext
  ): Route = routesWith(inboundWebSocket, outboundWebSocket)

  //scalastyle:off method.length
  def routesWith(
      inbound: InSocketArgs => Route,
      outbound: OutSocketArgs => Route
  )(
      implicit
      cfg: AppCfg,
      sys: ActorSystem,
      mat: ActorMaterializer,
      ctx: ExecutionContext
  ): Route = {
    pathPrefix("socket") {
      path("in") {
        inParams(args => inbound(args))
      } ~ path("out") {
        outParams(args => outbound(args))
      }
    } ~ pathPrefix("schemas") {
      pathPrefix("avro") {
        pathPrefix("producer") {
          path("record") {
            complete(
              HttpEntity(
                contentType = ContentTypes.`application/json`,
                string = AvroProducerRecord.schema.toString(true)
              )
            )
          } ~ path("result") {
            complete(
              HttpEntity(
                contentType = ContentTypes.`application/json`,
                string = AvroProducerResult.schema.toString(true)
              )
            )
          }
        } ~ pathPrefix("consumer") {
          path("record") {
            complete(
              HttpEntity(
                contentType = ContentTypes.`application/json`,
                string = AvroConsumerRecord.schema.toString(true)
              )
            )
          } ~ path("commit") {
            complete(
              HttpEntity(
                contentType = ContentTypes.`application/json`,
                string = AvroCommit.schema.toString(true)
              )
            )
          }
        }
      }
    }
  }
  //scalastyle:on method.length

}
