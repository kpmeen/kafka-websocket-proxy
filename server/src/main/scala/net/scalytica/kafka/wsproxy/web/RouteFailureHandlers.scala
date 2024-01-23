package net.scalytica.kafka.wsproxy.web

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.adapter._
import org.apache.pekko.http.scaladsl.marshalling.ToResponseMarshallable
import org.apache.pekko.http.scaladsl.model.StatusCodes._
import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server._
import org.apache.pekko.stream.Materializer
import org.apache.pekko.util.Timeout
import net.scalytica.kafka.wsproxy.errors._
import net.scalytica.kafka.wsproxy.logging.WithProxyLogger
import net.scalytica.kafka.wsproxy.models._
import net.scalytica.kafka.wsproxy.session.SessionHandlerImplicits._
import net.scalytica.kafka.wsproxy.session.{
  SessionHandlerProtocol,
  SessionId,
  SessionOpResult
}
import org.apache.kafka.common.KafkaException

import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

trait RouteFailureHandlers extends QueryParamParsers with WithProxyLogger {
  self: RoutesPrereqs =>

  implicit private[this] val sessionHandlerTimeout: Timeout = 3 seconds

  private val rejectionNoOp = (_: SessionId, _: FullClientId) => ()

  def rejectRequest(
      request: HttpRequest
  )(
      cleanup: (SessionId, FullClientId) => Unit
  )(c: => ToResponseMarshallable): Route = {
    paramsOnError(request) { args =>
      extractMaterializer { implicit mat =>
        args match {
          case ConsumerParamError(sid, cid, gid) =>
            val fid = FullConsumerId(gid, cid)
            cleanup(sid, fid)

          case ProducerParamError(sid, pid, ins) =>
            val fid = FullProducerId(pid, ins)
            cleanup(sid, fid)

          case other =>
            log.warn(s"Request rejected with ${other.niceClassSimpleName}")
        }

        request.discardEntityBytes()
        complete(c)
      }
    }
  }

  def rejectAndComplete(
      m: => ToResponseMarshallable
  )(cleanup: (SessionId, FullClientId) => Unit): Route = {
    extractRequest { request =>
      log.warn(
        s"Request ${request.method.value} ${request.uri.toString} failed"
      )
      rejectRequest(request)(cleanup)(m)
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
        )(rejectionNoOp)
      }
      .handleNotFound {
        rejectAndComplete(
          jsonResponseMsg(
            statusCode = NotFound,
            message = "This is not the resource you are looking for."
          )
        )(rejectionNoOp)
      }
      .result()
      .withFallback(RejectionHandler.default)
      .mapRejectionResponse { res =>
        res.entity match {
          case HttpEntity.Strict(ContentTypes.`text/plain(UTF-8)`, body) =>
            res.withEntity(
              HttpEntity(
                contentType = ContentTypes.`application/json`,
                string = jsonMessageFromString(body.utf8String)
              )
            )

          case _ => res
        }
      }
  }

  // scalastyle:off method.length
  def wsExceptionHandler(
      implicit sh: ActorRef[SessionHandlerProtocol.SessionProtocol],
      mat: Materializer
  ): ExceptionHandler =
    ExceptionHandler {
      case t: TopicNotFoundError =>
        extractUri { uri =>
          log.info(s"Topic in request $uri was not found.", t)
          val msg = jsonResponseMsg(BadRequest, t.message)
          rejectAndComplete(msg)(cleanupClient)
        }

      case r: RequestValidationError =>
        log.info(s"Request failed with RequestValidationError", r)
        val msg = jsonResponseMsg(BadRequest, r.msg)
        rejectAndComplete(msg)(cleanupClient)

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
          val msg = jsonResponseMsg(ServiceUnavailable, o.getMessage)
          rejectAndComplete(msg)(cleanupClient)
        }

      case k: KafkaException =>
        extractUri { uri =>
          log.warn(s"Request to $uri failed with a KafkaException.", k)
          val msg = jsonResponseMsg(InternalServerError, k.getMessage)
          rejectAndComplete(msg)(cleanupClient)
        }

      case t =>
        extractUri { uri =>
          log.warn(s"Request to $uri could not be handled normally", t)
          val msg = jsonResponseMsg(InternalServerError, t.getMessage)
          rejectAndComplete(msg)(cleanupClient)
        }
    }
  // scalastyle:on method.length

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

  private[this] def notAuthenticatedRejection(
      proxyErr: ProxyError,
      clientErr: ClientError
  ) = extractUri { uri =>
    log.info(s"Request to $uri could not be authenticated.", proxyErr)
    val msg = jsonResponseMsg(clientErr, proxyErr.getMessage)
    rejectAndComplete(msg)(rejectionNoOp)
  }

  private[this] def invalidTokenRejection(tokenErr: InvalidTokenError) =
    extractUri { uri =>
      log.info(s"JWT token in request $uri is not valid.", tokenErr)
      val msg = jsonResponseMsg(Unauthorized, tokenErr.getMessage)
      rejectAndComplete(msg)(rejectionNoOp)
    }

  protected def cleanupClient(
      sid: SessionId,
      fid: FullClientId
  )(
      implicit sh: ActorRef[SessionHandlerProtocol.SessionProtocol],
      mat: Materializer
  ): Unit = {
    implicit val ec        = mat.executionContext
    implicit val scheduler = mat.system.toTyped.scheduler

    fid match {
      case fcid: FullConsumerId =>
        sh.removeConsumer(fcid, serverId)
          .onComplete(removeClientComplete(sid, fid))

      case fpid: FullProducerId =>
        sh.removeProducer(fpid, serverId)
          .onComplete(removeClientComplete(sid, fid))
    }
  }
}
