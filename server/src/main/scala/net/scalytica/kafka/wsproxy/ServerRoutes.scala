package net.scalytica.kafka.wsproxy

import akka.actor.ActorSystem
import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{
  ExceptionHandler,
  RejectionHandler,
  Route,
  ValidationRejection
}
import akka.kafka.scaladsl.Consumer
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.RunnableGraph
import com.typesafe.scalalogging.Logger
import io.circe.{Json, Printer}
import net.scalytica.kafka.wsproxy.Configuration.AppCfg
import net.scalytica.kafka.wsproxy.avro.SchemaTypes.{
  AvroCommit,
  AvroConsumerRecord,
  AvroProducerRecord,
  AvroProducerResult
}
import net.scalytica.kafka.wsproxy.errors.TopicNotFoundError
import net.scalytica.kafka.wsproxy.models.{InSocketArgs, OutSocketArgs}
import net.scalytica.kafka.wsproxy.session.SessionHandler
import net.scalytica.kafka.wsproxy.websockets.{
  InboundWebSocket,
  OutboundWebSocket
}
import org.apache.avro.Schema

import scala.concurrent.ExecutionContext

trait ServerRoutes
    extends QueryParamParsers
    with OutboundWebSocket
    with InboundWebSocket {

  private[this] val logger = Logger(this.getClass)

  private[this] def jsonResponseMsg(
      statusCode: StatusCode,
      message: String
  ): HttpResponse = {
    val js = Json.obj("message" -> Json.fromString(message))
    HttpResponse(
      status = statusCode,
      entity = HttpEntity(
        contentType = ContentTypes.`application/json`,
        string = js.pretty(Printer.noSpaces)
      )
    )
  }

  private[this] def avroSchemaString(schema: Schema): ToResponseMarshallable = {
    HttpEntity(
      contentType = ContentTypes.`application/json`,
      string = schema.toString(true)
    )
  }

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
    case tnfe: TopicNotFoundError =>
      logger.info(s"Socket not initialised. Reason: ${tnfe.getMessage}")
      complete(jsonResponseMsg(NotFound, tnfe.getMessage))
    case t =>
      extractUri { uri =>
        logger.warn(s"Request to $uri could not be handled normally", t)
        complete(jsonResponseMsg(InternalServerError, t.getMessage))
      }
  }

  implicit def serverRejectionHandler: RejectionHandler = {
    RejectionHandler
      .newBuilder()
      .handleNotFound {
        rejectAndComplete(
          jsonResponseMsg(
            statusCode = NotFound,
            message = "This is not the resource you are looking for."
          )
        )
      }
      .handle {
        case ValidationRejection(msg, _) =>
          rejectAndComplete(jsonResponseMsg(BadRequest, msg))
      }
      .result()
      .withFallback(RejectionHandler.default)
  }

  def wsProxyRoutes(
      implicit
      cfg: AppCfg,
      sys: ActorSystem,
      mat: ActorMaterializer,
      ctx: ExecutionContext
  ): (RunnableGraph[Consumer.Control], Route) = {
    implicit val (sdcStream, sh) = SessionHandler.init
    (sdcStream, routesWith(inboundWebSocket, outboundWebSocket))
  }

  //scalastyle:off method.length
  def routesWith(
      inbound: InSocketArgs => Route,
      outbound: OutSocketArgs => Route
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
            complete(avroSchemaString(AvroProducerRecord.schema))
          } ~ path("result") {
            complete(avroSchemaString(AvroProducerResult.schema))
          }
        } ~ pathPrefix("consumer") {
          path("record") {
            complete(avroSchemaString(AvroConsumerRecord.schema))
          } ~ path("commit") {
            complete(avroSchemaString(AvroCommit.schema))
          }
        }
      }
    }
  }
  //scalastyle:on method.length

}
