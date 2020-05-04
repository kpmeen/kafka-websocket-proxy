package net.scalytica.kafka.wsproxy

import akka.actor.ActorSystem
import akka.actor.typed.scaladsl.adapter._
import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{BasicHttpCredentials, HttpCredentials}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{
  ExceptionHandler,
  RejectionHandler,
  Route,
  ValidationRejection
}
import akka.kafka.scaladsl.Consumer
import akka.stream.Materializer
import akka.stream.scaladsl.RunnableGraph
import akka.util.Timeout
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
import net.scalytica.kafka.wsproxy.logging.WithProxyLogger
import net.scalytica.kafka.wsproxy.models.{
  AclCredentials,
  InSocketArgs,
  OutSocketArgs,
  SocketArgs
}
import net.scalytica.kafka.wsproxy.session.SessionHandler.{
  SessionHandlerOpExtensions,
  SessionHandlerRef
}
import net.scalytica.kafka.wsproxy.session._
import net.scalytica.kafka.wsproxy.websockets.{
  InboundWebSocket,
  OutboundWebSocket
}
import org.apache.avro.Schema
import org.apache.kafka.common.KafkaException

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.Try

/**
 *
 */
trait BaseRoutes extends QueryParamParsers with WithProxyLogger {

  implicit private[this] val timeout: Timeout = 3 seconds

  protected def sessionHandler: Option[SessionHandlerRef] = None

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
        string = jsonMessageStr(message).printWith(Printer.noSpaces)
      )
    )
  }

  private[this] def rejectRequest(
      request: HttpRequest
  )(c: => ToResponseMarshallable) = {
    paramsOnError { args =>
      extractActorSystem { implicit sys =>
        extractMaterializer { implicit mat =>
          implicit val s = sys.scheduler.toTyped
          implicit val e = sys.dispatcher

          args.foreach {
            case (cid, gid) =>
              sessionHandler.foreach { sh =>
                sh.shRef.removeConsumer(gid, cid).onComplete {
                  case util.Success(res) =>
                    logger.debug(
                      s"Removing consumer ${cid.value} from group" +
                        s" ${gid.value} returned: ${res.asString}"
                    )
                  case util.Failure(err) =>
                    logger.warn(
                      "An error occurred when trying to remove consumer" +
                        s" ${cid.value} from group" +
                        s" ${gid.value}",
                      err
                    )
                }
              }
          }

          request.discardEntityBytes()
          complete(c)
        }
      }
    }
  }

  /**
   *
   * @return
   */
  private[this] def rejectAndComplete(
      m: => ToResponseMarshallable
  ) = {
    extractRequest { request =>
      logger.warn(
        s"Request ${request.method.value} ${request.uri.toString} failed"
      )
      rejectRequest(request)(m)
    }
  }

  /**
   *
   * @return
   */
  implicit def serverErrorHandler: ExceptionHandler =
    ExceptionHandler {
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
      .handle {
        case ValidationRejection(msg, _) =>
          rejectAndComplete(
            jsonResponseMsg(
              statusCode = BadRequest,
              message = msg
            )
          )
      }
      .result()
      .withFallback(RejectionHandler.default)
      .mapRejectionResponse { res =>
        res.entity match {
          case HttpEntity.Strict(ContentTypes.`text/plain(UTF-8)`, body) =>
            val js = jsonMessageStr(body.utf8String).printWith(Printer.noSpaces)
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

  private[this] var sessionHandlerRef: SessionHandlerRef = _

  override protected def sessionHandler: Option[SessionHandlerRef] =
    Some(sessionHandlerRef)

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
      mat: Materializer,
      ctx: ExecutionContext
  ): (RunnableGraph[Consumer.Control], Route) = {
    sessionHandlerRef = SessionHandler.init
    implicit val sh = sessionHandlerRef.shRef

    (sessionHandlerRef.stream, routesWith(inboundWebSocket, outboundWebSocket))
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
                    string = ci.asJson.printWith(Printer.spaces2)
                  )
                )
              } finally {
                admin.close()
              }
            }
          }
        }
      } ~
      path("healthcheck") {
        complete {
          HttpResponse(
            status = OK,
            entity = HttpEntity(
              contentType = ContentTypes.`application/json`,
              string = """{ "response": "I'm healthy" }"""
            )
          )
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
   * @param args The socket args provided
   * @param webSocketHandler lazy initializer for the websocket handler to use
   * @param cfg The configured [[AppCfg]]
   * @return Route that validates and handles websocket connections
   */
  private[this] def validateAndHandleWebSocket(
      args: SocketArgs
  )(
      webSocketHandler: => Route
  )(implicit cfg: AppCfg): Route = {
    val topic = args.topic
    logger.trace(s"Verifying if topic $topic exists...")
    val admin = new WsKafkaAdminClient(cfg)
    val topicExists = Try(admin.topicExists(topic)) match {
      case scala.util.Success(v) => v
      case scala.util.Failure(t) =>
        logger
          .warn(s"An error occurred while checking if topic $topic exists", t)
        false
    }
    admin.close()

    if (topicExists) webSocketHandler
    else reject(ValidationRejection(s"Topic ${topic.value} does not exist"))
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
