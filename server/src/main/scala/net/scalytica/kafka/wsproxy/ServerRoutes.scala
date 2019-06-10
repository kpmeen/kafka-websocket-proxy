package net.scalytica.kafka.wsproxy

import akka.actor.ActorSystem
import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{BasicHttpCredentials, HttpCredentials}
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
import net.scalytica.kafka.wsproxy.errors.{
  AuthenticationError,
  AuthorisationError,
  TopicNotFoundError
}
import net.scalytica.kafka.wsproxy.models.{
  AclCredentials,
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
import org.apache.kafka.common.KafkaException

import scala.concurrent.ExecutionContext

/**
 *
 */
trait BaseRoutes extends QueryParamParsers {
  private[this] val logger = Logger(classOf[BaseRoutes])

  /**
   *
   * @return
   */
  private[this] def jsonMessageStr(msg: String): Json =
    Json.obj("message" -> Json.fromString(msg))

  /**
   *
   * @return
   */
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

  /**
   *
   * @return
   */
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

  /**
   *
   * @return
   */
  implicit def serverErrorHandler: ExceptionHandler = ExceptionHandler {
    case t: TopicNotFoundError =>
      extractUri { uri =>
        logger.info(s"Topic in request $uri was not found.", t)
        rejectAndComplete(jsonResponseMsg(BadRequest, t.message))
      }

    case a: AuthenticationError =>
      extractUri { uri =>
        logger.info(s"Request to $uri could not be authenticated.", a)
        rejectAndComplete(jsonResponseMsg(Unauthorized, a.message))
      }

    case a: AuthorisationError =>
      extractUri { uri =>
        logger.info(s"Request to $uri could not be authenticated.", a)
        rejectAndComplete(jsonResponseMsg(Forbidden, a.message))
      }

    case k: KafkaException =>
      extractUri { uri =>
        logger.warn(s"Request to $uri failed with a KafkaException.", k)
        rejectAndComplete(jsonResponseMsg(InternalServerError, k.getMessage))
      }

    case t =>
      extractUri { uri =>
        logger.warn(s"Request to $uri could not be handled normally", t)
        rejectAndComplete(jsonResponseMsg(InternalServerError, t.getMessage))
      }
  }

  /**
   *
   * @return
   */
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
}

trait ServerRoutes
    extends BaseRoutes
    with OutboundWebSocket
    with InboundWebSocket
    with SchemaRoutes
    with WebSocketRoutes { self =>

  /**
   *
   * @param cfg
   * @param sys
   * @param mat
   * @param ctx
   * @return
   */
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

  /**
   *
   * @param inbound
   * @param outbound
   * @param cfg
   * @return
   */
  def routesWith(
      inbound: InSocketArgs => Route,
      outbound: OutSocketArgs => Route
  )(
      implicit
      cfg: AppCfg
  ): Route = {
    schemaRoutes ~
      websocketRoutes(inbound, outbound) ~
      pathPrefix("kafka") {
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

}

/**
 *
 */
trait SchemaRoutes { self: BaseRoutes =>

  private[this] def avroSchemaString(schema: Schema): ToResponseMarshallable = {
    HttpEntity(
      contentType = ContentTypes.`application/json`,
      string = schema.toString(true)
    )
  }

  /**
   *
   * @return
   */
  def schemaRoutes: Route = {
    pathPrefix("schemas") {
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

}

/**
 *
 */
trait WebSocketRoutes { self: BaseRoutes =>

  /**
   *
   * @param args
   * @param webSocketHandler
   * @param cfg
   * @return
   */
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

  /**
   *
   * @param creds
   * @return
   */
  private[this] def aclCredentials(creds: Option[HttpCredentials]) =
    creds match {
      case Some(BasicHttpCredentials(uname, pass)) =>
        Some(AclCredentials(uname, pass))
      case _ =>
        None
    }

  /**
   *
   * @param inbound
   * @param outbound
   * @param cfg
   * @return
   */
  def websocketRoutes(
      inbound: InSocketArgs => Route,
      outbound: OutSocketArgs => Route
  )(
      implicit
      cfg: AppCfg
  ): Route = {
    pathPrefix("socket") {
      path("in") {
        extractCredentials { creds =>
          inParams { inArgs =>
            val args = inArgs.withAclCredentials(aclCredentials(creds))
            validateAndHandleWebSocket(args)(inbound(args))
          }
        }
      } ~ path("out") {
        extractCredentials { creds =>
          outParams { outArgs =>
            val args = outArgs.withAclCredentials(aclCredentials(creds))
            validateAndHandleWebSocket(args)(outbound(args))
          }
        }
      }
    }
  }

}
