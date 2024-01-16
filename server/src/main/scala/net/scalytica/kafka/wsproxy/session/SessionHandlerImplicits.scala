package net.scalytica.kafka.wsproxy.session

import org.apache.pekko.actor.typed.{ActorRef, Scheduler}
import org.apache.pekko.actor.typed.scaladsl.AskPattern._
import org.apache.pekko.util.Timeout
import net.scalytica.kafka.wsproxy.actor.ActorWithProtocolExtensions
import net.scalytica.kafka.wsproxy.config.Configuration.{
  ClientSpecificLimitCfg,
  ConsumerSpecificLimitCfg,
  ProducerSpecificLimitCfg
}
import net.scalytica.kafka.wsproxy.errors.{
  FatalProxyServerError,
  RetryFailedError
}
import net.scalytica.kafka.wsproxy.logging.WithProxyLogger
import net.scalytica.kafka.wsproxy.models.{
  FullConsumerId,
  FullProducerId,
  WsGroupId,
  WsProducerId,
  WsServerId
}
import net.scalytica.kafka.wsproxy.session.SessionHandlerProtocol._
import net.scalytica.kafka.wsproxy.utils.BlockingRetry

import java.util.concurrent.TimeoutException
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

object SessionHandlerImplicits extends WithProxyLogger {

  implicit class SessionHandlerProtocolExtensions(val ref: ActorRef[Protocol])
      extends ActorWithProtocolExtensions[Protocol, SessionOpResult] {

    private[this] def handleClientError[T](
        sessionId: SessionId,
        appId: Option[String],
        instanceId: Option[String],
        serverId: Option[String]
    )(timeoutResponse: String => T)(throwable: Throwable): Future[T] = {
      val op = throwable.getStackTrace
        .dropWhile(ste => !ste.getClassName.equals(getClass.getName))
        .headOption
        .map(_.getMethodName)
        .getOrElse("unknown")
      val cid        = instanceId.map(c => s"for $c").getOrElse("")
      val sid        = serverId.map(s => s"on $s").getOrElse("")
      val gid        = appId.map(g => s"in $g").getOrElse("")
      val timeoutMsg = s"Timeout calling $op $cid $gid in $sessionId $sid"
      val otherMsg = s"Unhandled error calling $op $cid $gid in $sessionId $sid"

      throwable match {
        case t: TimeoutException =>
          log.debug(timeoutMsg, t)
          Future.successful(timeoutResponse(t.getMessage))

        case t: Throwable =>
          log.warn(otherMsg, t)
          throw t
      }
    }

    private[this] def handleClientSessionOpError(
        sessionId: SessionId,
        appId: Option[String] = None,
        instanceId: Option[String] = None,
        serverId: Option[String] = None
    )(throwable: Throwable): Future[SessionOpResult] = {
      handleClientError[SessionOpResult](
        sessionId = sessionId,
        appId = appId,
        instanceId = instanceId,
        serverId = serverId
      )(reason => IncompleteOperation(reason))(throwable)
    }

    def initConsumerSession(groupId: WsGroupId, consumerLimit: Int)(
        implicit ec: ExecutionContext,
        timeout: Timeout,
        scheduler: Scheduler
    ): Future[SessionOpResult] = {
      val sid = SessionId(groupId)
      doAsk(ref => InitConsumerSession(sid, groupId, consumerLimit, ref))
        .recoverWith(handleClientSessionOpError(sid)(_))
    }

    def initProducerSession(producerId: WsProducerId, maxClients: Int)(
        implicit ec: ExecutionContext,
        timeout: Timeout,
        scheduler: Scheduler
    ): Future[SessionOpResult] = {
      val sid = SessionId(producerId)
      doAsk(ref => InitProducerSession(sid, producerId, maxClients, ref))
        .recoverWith(handleClientSessionOpError(sid)(_))
    }

    def addConsumer(
        fullConsumerId: FullConsumerId,
        serverId: WsServerId
    )(
        implicit ec: ExecutionContext,
        timeout: Timeout,
        scheduler: Scheduler
    ): Future[SessionOpResult] = {
      val sid = SessionId(fullConsumerId.groupId)
      doAsk(ref => AddConsumer(sid, serverId, fullConsumerId, ref)).recoverWith(
        handleClientSessionOpError(
          sessionId = sid,
          appId = Option(fullConsumerId.groupId.value),
          instanceId = Option(fullConsumerId.clientId.value),
          serverId = Option(serverId.value)
        )(_)
      )
    }

    def addProducer(
        fullProducerId: FullProducerId,
        serverId: WsServerId
    )(
        implicit ec: ExecutionContext,
        timeout: Timeout,
        scheduler: Scheduler
    ): Future[SessionOpResult] = {
      val sid = SessionId(fullProducerId.producerId)
      doAsk(ref => AddProducer(sid, serverId, fullProducerId, replyTo = ref))
        .recoverWith(
          handleClientSessionOpError(
            sessionId = sid,
            appId = Option(fullProducerId.producerId.value),
            instanceId = fullProducerId.instanceId.map(_.value),
            serverId = Option(serverId.value)
          )(_)
        )
    }

    def removeConsumer(
        fullConsumerId: FullConsumerId,
        serverId: WsServerId
    )(
        implicit ec: ExecutionContext,
        timeout: Timeout,
        scheduler: Scheduler
    ): Future[SessionOpResult] = {
      val sid = SessionId(fullConsumerId.groupId)
      doAsk(ref => RemoveConsumer(sid, fullConsumerId, ref)).recoverWith(
        handleClientSessionOpError(
          sessionId = sid,
          appId = Option(fullConsumerId.groupId.value),
          instanceId = Option(fullConsumerId.clientId.value),
          serverId = Option(serverId.value)
        )(_)
      )
    }

    def removeProducer(
        fullProducerId: FullProducerId,
        serverId: WsServerId
    )(
        implicit ec: ExecutionContext,
        timeout: Timeout,
        scheduler: Scheduler
    ): Future[SessionOpResult] = {
      removeProducer(
        sessionId = SessionId(fullProducerId.producerId),
        fullProducerId = fullProducerId,
        serverId = serverId
      )
    }

    def removeProducer(
        sessionId: SessionId,
        fullProducerId: FullProducerId,
        serverId: WsServerId
    )(
        implicit ec: ExecutionContext,
        timeout: Timeout,
        scheduler: Scheduler
    ): Future[SessionOpResult] = {
      doAsk(ref => RemoveProducer(sessionId, fullProducerId, ref)).recoverWith(
        handleClientSessionOpError(
          sessionId = sessionId,
          appId = Option(fullProducerId.producerId.value),
          instanceId = fullProducerId.instanceId.map(_.value),
          serverId = Option(serverId.value)
        )(_)
      )
    }

    def awaitSessionRestoration()(
        implicit ec: ExecutionContext,
        scheduler: Scheduler
    ): SessionStateRestored = {
      val retries = 100
      BlockingRetry.retryAwaitFuture(60 seconds, 500 millis, retries) {
        attemptTimeout =>
          implicit val at: Timeout = attemptTimeout

          doAsk(ref => CheckSessionHandlerReady(ref)).map {
            case ssr: SessionStateRestored =>
              log.trace("Session state is restored.")
              ssr

            case _: RestoringSessionState =>
              log.trace("Session state is still being restored...")
              throw RetryFailedError("Session state not ready.")

            case _ =>
              throw FatalProxyServerError(
                "Unexpected error when checking for state of session topic " +
                  "restoration. Expected one of:" +
                  s"[${classOf[SessionStateRestored].niceClassSimpleName} |" +
                  s" ${classOf[RestoringSessionState].niceClassSimpleName}]"
              )
          }
      } { t =>
        throw FatalProxyServerError(
          message = "Unable to restore session state",
          cause = Option(t)
        )
      }
    }

    def sessionHandlerStop()(
        implicit ec: ExecutionContext,
        timeout: Timeout,
        scheduler: Scheduler
    ): Future[SessionOpResult] = {
      doAsk(ref => StopSessionHandler(ref)).recoverWith {
        case t: TimeoutException =>
          log.debug("Timeout calling sessionHandlerStop()", t)
          Future.successful(IncompleteOperation(t.getMessage))

        case t: Throwable =>
          log.warn("Unknown error calling sessionHandlerStop()", t)
          throw t
      }
    }

    def updateSessionConfig(cslCfg: ClientSpecificLimitCfg)(
        implicit ec: ExecutionContext,
        timeout: Timeout,
        scheduler: Scheduler
    ): Future[SessionOpResult] = {
      val res = cslCfg match {
        case consCfg: ConsumerSpecificLimitCfg =>
          val sid = SessionId(consCfg.groupId)
          doAskWithStandardRecovery { ref =>
            UpdateConsumerSession(sid, consCfg, ref)
          } { case (msg, _) =>
            IncompleteOperation(msg)
          }

        case prodCfg: ProducerSpecificLimitCfg =>
          val sid = SessionId(prodCfg.producerId)
          doAsk { ref =>
            UpdateProducerSession(sid, prodCfg, ref)
          }
      }

      res.recoverWith {
        case t: TimeoutException =>
          log.debug("Timeout calling updateSessionConfig()", t)
          Future.successful(IncompleteOperation(t.getMessage))

        case t: Throwable =>
          log.warn("Unknown error calling updateSessionConfig()", t)
          throw t
      }
    }

    def getConsumerSession(groupId: WsGroupId)(
        implicit ec: ExecutionContext,
        timeout: Timeout,
        scheduler: Scheduler
    ): Future[Option[ConsumerSession]] = {
      val sid = SessionId(groupId)
      ref
        .ask[Option[ConsumerSession]](ref => GetConsumerSession(sid, ref))
        .recoverWith(
          handleClientError[Option[ConsumerSession]](
            sessionId = sid,
            appId = Option(groupId.value),
            instanceId = None,
            serverId = None
          )(_ => None)(_)
        )
    }

    def getProducerSession(producerId: WsProducerId)(
        implicit ec: ExecutionContext,
        timeout: Timeout,
        scheduler: Scheduler
    ): Future[Option[ProducerSession]] = {
      val sid = SessionId(producerId)
      ref
        .ask[Option[ProducerSession]](ref => GetProducerSession(sid, ref))
        .recoverWith(
          handleClientError[Option[ProducerSession]](
            sessionId = sid,
            appId = Option(producerId.value),
            instanceId = None,
            serverId = None
          )(_ => None)(_)
        )
    }

  }
}
