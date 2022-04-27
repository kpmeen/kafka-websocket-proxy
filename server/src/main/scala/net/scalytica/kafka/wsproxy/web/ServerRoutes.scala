package net.scalytica.kafka.wsproxy.web

import akka.actor.ActorSystem
import akka.actor.typed.scaladsl.adapter._
import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.http.scaladsl.server.directives.Credentials
import akka.kafka.scaladsl.Consumer
import akka.stream.Materializer
import akka.stream.scaladsl.RunnableGraph
import akka.util.Timeout
import io.circe.syntax._
import io.circe.{Json, Printer}
import net.scalytica.kafka.wsproxy.admin.WsKafkaAdminClient
import net.scalytica.kafka.wsproxy.auth.OpenIdClient
import net.scalytica.kafka.wsproxy.avro.SchemaTypes.{
  AvroCommit,
  AvroConsumerRecord,
  AvroProducerRecord,
  AvroProducerResult
}
import net.scalytica.kafka.wsproxy.codecs.Encoders.brokerInfoEncoder
import net.scalytica.kafka.wsproxy.config.Configuration.AppCfg
import net.scalytica.kafka.wsproxy.errors._
import net.scalytica.kafka.wsproxy.jmx.JmxManager
import net.scalytica.kafka.wsproxy.logging.WithProxyLogger
import net.scalytica.kafka.wsproxy.models.{WsProxyAuthResult, _}
import net.scalytica.kafka.wsproxy.session.SessionHandlerImplicits._
import net.scalytica.kafka.wsproxy.session.SessionHandlerRef
import net.scalytica.kafka.wsproxy.session.{SessionId, SessionOpResult}
import net.scalytica.kafka.wsproxy.web.Headers.XKafkaAuthHeader
import net.scalytica.kafka.wsproxy.web.websockets.{
  InboundWebSocket,
  OutboundWebSocket
}
import org.apache.avro.Schema
import org.apache.kafka.common.KafkaException

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

trait BaseRoutes extends QueryParamParsers with WithProxyLogger {

  implicit private[this] val sessionHandlerTimeout: Timeout = 3 seconds

  protected var sessionHandler: SessionHandlerRef = _

  protected val serverId: WsServerId

  protected def basicAuthCredentials(
      creds: Credentials
  )(implicit cfg: AppCfg): Option[WsProxyAuthResult] = {
    cfg.server.basicAuth
      .flatMap { bac =>
        for {
          u <- bac.username
          p <- bac.password
        } yield (u, p)
      }
      .map { case (usr, pwd) =>
        creds match {
          case p @ Credentials.Provided(id) // constant time comparison
              if usr.equals(id) && p.verify(pwd) =>
            log.trace("Successfully authenticated bearer token.")
            Some(BasicAuthResult(id))

          case _ =>
            log.info("Could not authenticate basic auth credentials")
            None
        }
      }
      .getOrElse(Some(AuthDisabled))
  }

  protected def openIdAuth(
      creds: Credentials
  )(
      implicit appCfg: AppCfg,
      maybeOpenIdClient: Option[OpenIdClient],
      mat: Materializer
  ): Future[Option[WsProxyAuthResult]] = {
    log.trace(s"Going to validate openid token $creds")
    implicit val ec = mat.executionContext

    maybeOpenIdClient match {
      case Some(oidcClient) =>
        creds match {
          case Credentials.Provided(token) =>
            val bearerToken = OAuth2BearerToken(token)
            oidcClient.validate(bearerToken).flatMap {
              case Success(jwtClaim) =>
                log.trace("Successfully authenticated bearer token.")
                val jar = JwtAuthResult(bearerToken, jwtClaim)
                if (jar.isValid) Future.successful(Some(jar))
                else Future.successful(None)
              case Failure(err) =>
                err match {
                  case err: ProxyAuthError =>
                    log.info("Could not authenticate bearer token", err)
                    Future.successful(None)
                  case err =>
                    Future.failed(err)
                }
            }
          case _ =>
            log.info("Could not authenticate bearer token")
            Future.successful(None)
        }
      case None =>
        log.info("OpenID Connect is not enabled")
        Future.successful(None)
    }
  }

  protected def maybeAuthenticateOpenId[T](
      implicit cfg: AppCfg,
      maybeOpenIdClient: Option[OpenIdClient],
      mat: Materializer
  ): Directive1[WsProxyAuthResult] = {
    log.debug("Attempting authentication using openid-connect...")
    cfg.server.openidConnect
      .flatMap { oidcCfg =>
        val realm = oidcCfg.realm.getOrElse("")
        if (oidcCfg.enabled) Option(authenticateOAuth2Async(realm, openIdAuth))
        else None
      }
      .getOrElse {
        log.info("OpenID Connect is not enabled.")
        provide(AuthDisabled)
      }
  }

  protected def maybeAuthenticateBasic[T](
      implicit cfg: AppCfg
  ): Directive1[WsProxyAuthResult] = {
    log.debug("Attempting authentication using basic authentication...")
    cfg.server.basicAuth
      .flatMap { ba =>
        if (ba.enabled)
          ba.realm.map(r => authenticateBasic(r, basicAuthCredentials))
        else None
      }
      .getOrElse {
        log.info("Basic authentication is not enabled.")
        provide(AuthDisabled)
      }
  }

  protected def maybeAuthenticate[T](
      implicit cfg: AppCfg,
      maybeOpenIdClient: Option[OpenIdClient],
      mat: Materializer
  ): Directive1[WsProxyAuthResult] = {
    if (cfg.server.isOpenIdConnectEnabled) maybeAuthenticateOpenId[T]
    else if (cfg.server.isBasicAuthEnabled) maybeAuthenticateBasic[T]
    else provide(AuthDisabled)
  }

  private[this] def jsonMessageFromString(msg: String): Json =
    Json.obj("message" -> Json.fromString(msg))

  private[this] def jsonResponseMsg(
      statusCode: StatusCode,
      message: String
  ): HttpResponse = {
    HttpResponse(
      status = statusCode,
      entity = HttpEntity(
        contentType = ContentTypes.`application/json`,
        string = jsonMessageFromString(message).spaces2
      )
    )
  }

  private[this] def removeClientComplete(
      sid: SessionId,
      fid: FullClientId
  )(tryRes: Try[SessionOpResult]): Unit = {
    val (removingMsg, failMsg) = fid match {
      case FullConsumerId(gid, cid) =>
        val remStr = (msg: String) =>
          s"Removing consumer ${cid.value} from group ${gid.value} " +
            s"in session ${sid.value} on server ${serverId.value} " +
            s"returned: $msg"

        val errStr = "An error occurred when trying to remove consumer" +
          s" ${cid.value} from group ${gid.value} in session ${sid.value} " +
          s"on server ${serverId.value}."

        (remStr, errStr)

      case FullProducerId(pid, iid) =>
        val instStr = iid.map(i => s" instance ${i.value} for").getOrElse("")
        val remStr = (msg: String) =>
          s"Removing$instStr producer ${pid.value} in session ${sid.value} " +
            s"on server ${serverId.value} returned: $msg"

        val errStr = s"An error occurred when trying to remove$instStr" +
          s"producer ${pid.value} in session ${sid.value} " +
          s"on server ${serverId.value}."

        (remStr, errStr)
    }

    tryRes match {
      case Success(res) => log.debug(removingMsg(res.asString))
      case Failure(err) => log.warn(failMsg, err)
    }
  }

  private[this] def rejectRequest(
      request: HttpRequest
  )(c: => ToResponseMarshallable) = {
    paramsOnError(request) { args =>
      extractActorSystem { implicit sys =>
        extractMaterializer { implicit mat =>
          implicit val s = sys.scheduler.toTyped
          implicit val e = sys.dispatcher

          args match {
            case ConsumerParamError(sid, cid, gid) =>
              val fid = FullConsumerId(gid, cid)
              sessionHandler.shRef
                .removeConsumer(fid, serverId)
                .onComplete(removeClientComplete(sid, fid))

            case ProducerParamError(sid, pid, ins) =>
              val fid = FullProducerId(pid, ins)
              sessionHandler.shRef
                .removeProducer(fid, serverId)
                .onComplete(removeClientComplete(sid, fid))

            case wrong =>
              log.warn(
                "Insufficient information in request to remove internal " +
                  s"client references due to ${wrong.niceClassSimpleName}"
              )
          }

          request.discardEntityBytes()
          complete(c)
        }
      }
    }
  }

  private[this] def rejectAndComplete(
      m: => ToResponseMarshallable
  ) = {
    extractRequest { request =>
      log.warn(
        s"Request ${request.method.value} ${request.uri.toString} failed"
      )
      rejectRequest(request)(m)
    }
  }

  private[this] def notAuthenticatedRejection(
      proxyError: ProxyError,
      clientError: ClientError
  ) = extractUri { uri =>
    log.info(s"Request to $uri could not be authenticated.", proxyError)
    rejectAndComplete(jsonResponseMsg(clientError, proxyError.getMessage))
  }

  private[this] def invalidTokenRejection(tokenError: InvalidTokenError) =
    extractUri { uri =>
      log.info(s"JWT token in request $uri is not valid.", tokenError)
      rejectAndComplete(jsonResponseMsg(Unauthorized, tokenError.getMessage))
    }

  def wsExceptionHandler: ExceptionHandler =
    ExceptionHandler {
      case t: TopicNotFoundError =>
        extractUri { uri =>
          log.info(s"Topic in request $uri was not found.", t)
          rejectAndComplete(jsonResponseMsg(BadRequest, t.message))
        }

      case r: RequestValidationError =>
        log.info(s"Request failed with RequestValidationError", r)
        rejectAndComplete(jsonResponseMsg(BadRequest, r.msg))

      case i: InvalidPublicKeyError =>
        log.warn(s"Request failed with an InvalidPublicKeyError.", i)
        notAuthenticatedRejection(i, Unauthorized)

      case i: InvalidTokenError =>
        log.warn(s"Request failed with an InvalidTokenError.", i)
        invalidTokenRejection(i)

      case a: AuthenticationError =>
        log.warn(s"Request failed with an AuthenticationError.", a)
        notAuthenticatedRejection(a, Unauthorized)

      case a: AuthorisationError =>
        log.warn(s"Request failed with an AuthorizationError.", a)
        notAuthenticatedRejection(a, Forbidden)

      case o: OpenIdConnectError =>
        extractUri { uri =>
          log.warn(s"Request to $uri failed with an OpenIDConnectError.", o)
          rejectAndComplete(jsonResponseMsg(ServiceUnavailable, o.getMessage))
        }

      case k: KafkaException =>
        extractUri { uri =>
          log.warn(s"Request to $uri failed with a KafkaException.", k)
          rejectAndComplete(jsonResponseMsg(InternalServerError, k.getMessage))
        }

      case t =>
        extractUri { uri =>
          log.warn(s"Request to $uri could not be handled normally", t)
          rejectAndComplete(jsonResponseMsg(InternalServerError, t.getMessage))
        }
    }

  implicit def serverRejectionHandler: RejectionHandler = {
    RejectionHandler
      .newBuilder()
      .handle { case MissingQueryParamRejection(paramName) =>
        rejectAndComplete(
          jsonResponseMsg(
            statusCode = BadRequest,
            message = s"Request is missing required parameter '$paramName'"
          )
        )
      }
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
            val js = jsonMessageFromString(body.utf8String).noSpaces
            res.withEntity(HttpEntity(ContentTypes.`application/json`, js))

          case _ => res
        }
      }
  }
}

trait ServerRoutes
    extends BaseRoutes
    with OutboundWebSocket
    with InboundWebSocket
    with StatusRoutes
    with SchemaRoutes
    with WebSocketRoutes { self =>

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

trait StatusRoutes { self: BaseRoutes =>

  private[this] def serveHealthCheck = {
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

  private[this] def serveClusterInfo(implicit cfg: AppCfg) = {
    complete {
      val admin = new WsKafkaAdminClient(cfg)
      try {
        log.debug("Fetching Kafka cluster info...")
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

  def statusRoutes(
      implicit cfg: AppCfg,
      maybeOidcClient: Option[OpenIdClient]
  ): Route = {
    extractMaterializer { implicit mat =>
      pathPrefix("kafka") {
        pathPrefix("cluster") {
          path("info") {
            maybeAuthenticate(cfg, maybeOidcClient, mat)(_ => serveClusterInfo)
          }
        }
      } ~
        path("healthcheck") {
          if (cfg.server.secureHealthCheckEndpoint) {
            maybeAuthenticate(cfg, maybeOidcClient, mat)(_ => serveHealthCheck)
          } else {
            serveHealthCheck
          }
        }
    }
  }
}

trait SchemaRoutes { self: BaseRoutes =>

  private[this] def avroSchemaString(schema: Schema): ToResponseMarshallable = {
    HttpEntity(
      contentType = ContentTypes.`application/json`,
      string = schema.toString(true)
    )
  }

  def schemaRoutes(
      implicit cfg: AppCfg,
      maybeOpenIdClient: Option[OpenIdClient]
  ): Route = {
    extractMaterializer { implicit mat =>
      pathPrefix("schemas") {
        pathPrefix("avro") {
          pathPrefix("producer") {
            path("record") {
              maybeAuthenticate(cfg, maybeOpenIdClient, mat) { _ =>
                complete(avroSchemaString(AvroProducerRecord.schema))
              }
            } ~ path("result") {
              maybeAuthenticate(cfg, maybeOpenIdClient, mat) { _ =>
                complete(avroSchemaString(AvroProducerResult.schema))
              }
            }
          } ~ pathPrefix("consumer") {
            path("record") {
              maybeAuthenticate(cfg, maybeOpenIdClient, mat) { _ =>
                complete(avroSchemaString(AvroConsumerRecord.schema))
              }
            } ~ path("commit") {
              maybeAuthenticate(cfg, maybeOpenIdClient, mat) { _ =>
                complete(avroSchemaString(AvroCommit.schema))
              }
            }
          }
        }
      }
    }
  }

}

trait WebSocketRoutes { self: BaseRoutes =>

  /**
   * @param args
   *   The socket args provided
   * @param webSocketHandler
   *   lazy initializer for the websocket handler to use
   * @param cfg
   *   The configured [[AppCfg]]
   * @return
   *   Route that validates and handles websocket connections
   */
  private[this] def validateAndHandleWebSocket(
      args: SocketArgs
  )(
      webSocketHandler: => Route
  )(implicit cfg: AppCfg): Route = {
    val topic = args.topic
    log.trace(s"Verifying if topic $topic exists...")
    val admin = new WsKafkaAdminClient(cfg)
    val topicExists = Try(admin.topicExists(topic)) match {
      case scala.util.Success(v) => v
      case scala.util.Failure(t) =>
        log.warn(s"An error occurred while checking if topic $topic exists", t)
        false
    }
    admin.close()

    if (topicExists) webSocketHandler
    else reject(ValidationRejection(s"Topic ${topic.value} does not exist"))
  }

  private[this] def extractKafkaCreds(
      authRes: WsProxyAuthResult,
      kafkaAuthHeader: Option[XKafkaAuthHeader]
  )(implicit cfg: AppCfg): Option[AclCredentials] = {
    cfg.server.openidConnect
      .map { oidcfg =>
        if (oidcfg.isKafkaTokenAuthOnlyEnabled) {
          log.trace("Only allowing Kafka auth through JWT token.")
          authRes.aclCredentials
        } else {
          log.trace(
            s"Allowing Kafka auth through JWT token or the" +
              s" ${Headers.KafkaAuthHeaderName} header."
          )
          // Always prefer the JWT token
          authRes.aclCredentials.orElse(kafkaAuthHeader.map(_.aclCredentials))
        }
      }
      .getOrElse {
        log.trace(
          "OpenID Connect is not configured. Using" +
            s" ${Headers.KafkaAuthHeaderName} header."
        )
        kafkaAuthHeader.map(_.aclCredentials)
      }
  }

  /**
   * @param inbound
   *   function defining the [[Route]] for the producer socket
   * @param outbound
   *   function defining the [[Route]] for the consumer socket
   * @param cfg
   *   Implicitly provided [[AppCfg]]
   * @param maybeOpenIdClient
   *   Implicitly provided Option that contains an [[OpenIdClient]] if OIDC is
   *   enabled.
   * @return
   *   The [[Route]] definition for the websocket endpoints
   */
  def websocketRoutes(
      inbound: InSocketArgs => Route,
      outbound: OutSocketArgs => Route
  )(implicit cfg: AppCfg, maybeOpenIdClient: Option[OpenIdClient]): Route = {
    extractMaterializer { implicit mat =>
      pathPrefix("socket") {
        path("in") {
          maybeAuthenticate(cfg, maybeOpenIdClient, mat) { authResult =>
            optionalHeaderValueByType(XKafkaAuthHeader) { headerCreds =>
              val creds = extractKafkaCreds(authResult, headerCreds)
              inParams { inArgs =>
                val args = inArgs
                  .withAclCredentials(creds)
                  .withBearerToken(authResult.maybeBearerToken)
                validateAndHandleWebSocket(args)(inbound(args))
              }
            }
          }
        } ~ path("out") {
          maybeAuthenticate(cfg, maybeOpenIdClient, mat) { authResult =>
            optionalHeaderValueByType(XKafkaAuthHeader) { headerCreds =>
              val creds = extractKafkaCreds(authResult, headerCreds)
              outParams { outArgs =>
                val args = outArgs
                  .withAclCredentials(creds)
                  .withBearerToken(authResult.maybeBearerToken)
                validateAndHandleWebSocket(args)(outbound(args))
              }
            }
          }
        }
      }
    }
  }

}
