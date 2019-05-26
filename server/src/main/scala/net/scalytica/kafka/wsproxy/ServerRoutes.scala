package net.scalytica.kafka.wsproxy

import akka.actor.ActorSystem
import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{ExceptionHandler, RejectionHandler, Route}
import akka.kafka.scaladsl.Consumer
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.RunnableGraph
import com.typesafe.scalalogging.Logger
import io.circe.syntax._
import io.circe.{Json, Printer}
import net.scalytica.kafka.wsproxy.Configuration.AppCfg
import net.scalytica.kafka.wsproxy.admin.WsKafkaAdminClient
import net.scalytica.kafka.wsproxy.avro.SchemaTypes.{
  AvroCommit,
  AvroConsumerRecord,
  AvroProducerRecord,
  AvroProducerResult
}
import net.scalytica.kafka.wsproxy.codecs.Encoders.brokerInfoEncoder
import net.scalytica.kafka.wsproxy.errors.TopicNotFoundError
import net.scalytica.kafka.wsproxy.models.{
  InSocketArgs,
  OutSocketArgs,
  SocketArgs
}
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
    with InboundWebSocket { self =>

  private[this] val logger = Logger(classOf[ServerRoutes])

  private[this] def jsonMessageStr(msg: String): Json =
    Json.obj("message" -> Json.fromString(msg))

  private[this] def jsonResponseMsg(
      statusCode: StatusCode,
      message: String
  ): HttpResponse = {
    HttpResponse(
      status = statusCode,
      entity = HttpEntity(
        contentType = ContentTypes.`application/json`,
        string = jsonMessageStr(message).pretty(Printer.noSpaces)
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
    case tnf: TopicNotFoundError =>
      extractUri { uri =>
        logger.info(s"WebSocket request $uri failed. Reason: ${tnf.getMessage}")
        rejectAndComplete(jsonResponseMsg(BadRequest, tnf.getMessage))
      }

    case t =>
      extractUri { uri =>
        logger.warn(s"Request to $uri could not be handled normally", t)
        rejectAndComplete(jsonResponseMsg(InternalServerError, t.getMessage))
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
      .result()
      .withFallback(RejectionHandler.default)
      .mapRejectionResponse { res =>
        res.entity match {
          case HttpEntity.Strict(ContentTypes.`text/plain(UTF-8)`, body) =>
            val js = jsonMessageStr(body.utf8String).pretty(Printer.noSpaces)
            res.copy(entity = HttpEntity(ContentTypes.`application/json`, js))
          case _ => res
        }
      }
  }

  private[this] def validateAndHandleWebSocket(
      args: SocketArgs
  )(
      webSocketHandler: Route
  )(implicit cfg: AppCfg): Route = {
    val topic = args.topic
    val admin = new WsKafkaAdminClient(cfg)
    val topicExists = try { admin.topicExists(topic) } finally {
      admin.close()
    }

    if (topicExists) webSocketHandler
    else failWith(TopicNotFoundError(s"Topic ${topic.value} does not exist"))
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
  )(
      implicit
      cfg: AppCfg
  ): Route = {
    pathPrefix("socket") {
      path("in") {
        inParams(args => validateAndHandleWebSocket(args)(inbound(args)))
      } ~ path("out") {
        outParams(args => validateAndHandleWebSocket(args)(outbound(args)))
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
    } ~ pathPrefix("kafka") {
      pathPrefix("cluster") {
        path("info") {
          complete {
            val admin = new WsKafkaAdminClient(cfg)
            try {
              val ci = admin.clusterInfo

              HttpResponse(
                status = OK,
                entity = HttpEntity(
                  contentType = ContentTypes.`application/json`,
                  string = ci.asJson.pretty(Printer.spaces2)
                )
              )
            } finally {
              admin.close()
            }
          }
        }
      }
    }
  }
  //scalastyle:on method.length

}
