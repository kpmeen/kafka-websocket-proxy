package net.scalytica.kafka.wsproxy.session

import akka.Done
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.{ActorRef, Behavior, Scheduler}
import akka.kafka.scaladsl.Consumer
import akka.stream.scaladsl.{RunnableGraph, Sink}
import akka.util.Timeout
import akka.util.Timeout.durationToTimeout
import net.scalytica.kafka.wsproxy.admin.WsKafkaAdminClient
import net.scalytica.kafka.wsproxy.config.Configuration.AppCfg
import net.scalytica.kafka.wsproxy.errors.{
  FatalProxyServerError,
  RetryFailedError
}
import net.scalytica.kafka.wsproxy.logging.WithProxyLogger
import net.scalytica.kafka.wsproxy.models.{
  FullClientId,
  FullConsumerId,
  FullProducerId,
  WsGroupId,
  WsProducerId,
  WsServerId
}
import net.scalytica.kafka.wsproxy.session.SessionHandlerProtocol._
import net.scalytica.kafka.wsproxy.utils.BlockingRetry

import java.util.concurrent.TimeoutException
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

object SessionHandler extends SessionHandler {

  case class SessionHandlerRef(
      stream: RunnableGraph[Consumer.Control],
      shRef: ActorRef[SessionHandlerProtocol.Protocol]
  )

  implicit class SessionHandlerOpExtensions(
      sh: ActorRef[SessionHandlerProtocol.Protocol]
  ) {
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
      val cid = instanceId.map(c => s"for $c").getOrElse("")
      val sid = serverId.map(s => s"on $s").getOrElse("")
      val gid = appId.map(g => s"in $g").getOrElse("")

      throwable match {
        case t: TimeoutException =>
          log.debug(s"Timeout calling $op $cid $gid in $sessionId $sid", t)
          Future.successful(timeoutResponse(t.getMessage))

        case t: Throwable =>
          log.warn(
            s"Unhandled error calling $op $cid $gid in $sessionId $sid",
            t
          )
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
      sh.ask[SessionOpResult] { ref =>
        SessionHandlerProtocol.InitConsumerSession(
          sessionId = sid,
          groupId = groupId,
          maxConnections = consumerLimit,
          replyTo = ref
        )
      }.recoverWith(handleClientSessionOpError(sid)(_))
    }

    def initProducerSession(producerId: WsProducerId, maxClients: Int)(
        implicit ec: ExecutionContext,
        timeout: Timeout,
        scheduler: Scheduler
    ): Future[SessionOpResult] = {
      val sid = SessionId(producerId)
      sh.ask[SessionOpResult] { ref =>
        SessionHandlerProtocol.InitProducerSession(
          sessionId = sid,
          producerId = producerId,
          maxConnections = maxClients,
          replyTo = ref
        )
      }.recoverWith(handleClientSessionOpError(sid)(_))
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
      sh.ask[SessionOpResult] { ref =>
        SessionHandlerProtocol.AddConsumer(
          sessionId = sid,
          serverId = serverId,
          fullClientId = fullConsumerId,
          replyTo = ref
        )
      }.recoverWith(
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
      sh.ask[SessionOpResult] { ref =>
        SessionHandlerProtocol.AddProducer(
          sessionId = sid,
          serverId = serverId,
          fullClientId = fullProducerId,
          replyTo = ref
        )
      }.recoverWith(
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

      sh.ask[SessionOpResult] { ref =>
        SessionHandlerProtocol.RemoveConsumer(
          sessionId = sid,
          fullClientId = fullConsumerId,
          replyTo = ref
        )
      }.recoverWith(
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
      sh.ask[SessionOpResult] { ref =>
        SessionHandlerProtocol.RemoveProducer(
          sessionId = sessionId,
          fullClientId = fullProducerId,
          replyTo = ref
        )
      }.recoverWith(
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

          sh.ask[SessionOpResult] { ref =>
            SessionHandlerProtocol.SessionHandlerReady(ref)
          }.map {
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

    def sessionHandlerShutdown()(
        implicit ec: ExecutionContext,
        timeout: Timeout,
        scheduler: Scheduler
    ): Future[SessionOpResult] = {
      sh.ask[SessionOpResult] { ref =>
        SessionHandlerProtocol.StopSessionHandler(ref)
      }.recoverWith {
        case t: TimeoutException =>
          log.debug("Timeout calling sessionShutdown()", t)
          Future.successful(IncompleteOperation(t.getMessage))

        case t: Throwable =>
          log.warn("An unknown error occurred calling sessionShutdown()", t)
          throw t
      }
    }

    def getConsumerSession(groupId: WsGroupId)(
        implicit ec: ExecutionContext,
        timeout: Timeout,
        scheduler: Scheduler
    ): Future[Option[ConsumerSession]] = {
      val sid = SessionId(groupId)
      sh.ask[Option[ConsumerSession]] { ref =>
        SessionHandlerProtocol.GetConsumerSession(sid, ref)
      }.recoverWith(
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
      sh.ask[Option[ProducerSession]] { ref =>
        SessionHandlerProtocol.GetProducerSession(sid, ref)
      }.recoverWith(
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

/**
 * Logic for building a clustered session handler using Kafka.
 *
 * The idea is to use a compacted Kafka topic as the source of truth for client
 * session state. Each instance of the kafka-websocket-proxy will have a single
 * actor that can read and write from/to that topic. The topic data should
 * always be the up-to-date version of the state for all proxy instances, and we
 * can therefore implement some nice features on top of that capability.
 *
 *   - Keeping track of number of sockets per session
 *   - Keeping track of which clients are part of the session
 *   - Possibility to share meta-data across nodes
 *   - etc...
 */
trait SessionHandler extends WithProxyLogger {

  private[this] var latestOffset: Long     = 0L
  private[this] var stateRestored: Boolean = false

  /**
   * Calculates the expected next behaviour based on the value of the {{either}}
   * argument.
   *
   * @param either
   *   the value to calculate the next behaviour from.
   * @param behavior
   *   the behaviour to use in the case of a rhs value.
   * @return
   *   the next behaviour to use.
   */
  private[this] def nextOrSameBehavior(
      either: Either[String, ActiveSessions]
  )(
      behavior: ActiveSessions => Behavior[Protocol]
  ): Behavior[Protocol] = {
    either match {
      case Left(_)   => Behaviors.same
      case Right(ns) => behavior(ns)
    }
  }

  private[this] def prepareStateTopic(implicit cfg: AppCfg): Long = {
    val admin = new WsKafkaAdminClient(cfg)
    try {
      val ready = admin.clusterReady
      if (ready) admin.initSessionStateTopic()
      else {
        log.error("Could not reach Kafka cluster. Terminating application!")
        System.exit(1)
      }
      val offset = admin.lastOffsetForSessionStateTopic
      log.debug(s"Session state topic ready. Latest offset is $offset")
      if (latestOffset == 0) stateRestored = true
      offset
    } finally {
      admin.close()
    }
  }

  private[this] def verifySessionStateRestore(
      ip: InternalSessionProtocol
  ): Unit = {
    if (!stateRestored && latestOffset <= ip.offset) {
      stateRestored = true
      log.info(s"Session state restored to offset ${ip.offset}/$latestOffset")
    }
  }

  /**
   * Potentially clean the existing sessions for clients belonging to this
   * serverId. Clients may get stuck in a session if the server has an unclean
   * shutdown. If there are no more clients left in the session after cleanup,
   * the [[UpdateSession]] command is converted into a [[RemoveSession]]
   * command.
   *
   * @param upd
   *   [[UpdateSession]] to verify and clean
   * @param cfg
   *   the implicitly provided [[AppCfg]]
   * @return
   *   The sanitized [[InternalSessionProtocol]]
   */
  private[this] def sanitizeSessionOnRestore(
      upd: UpdateSession
  )(implicit cfg: AppCfg): InternalSessionProtocol = {
    // Clean up after a potentially unclean restart
    if (upd.s.instances.exists(_.serverId == cfg.server.serverId)) {
      val s2 = upd.s.removeInstancesFromServerId(cfg.server.serverId)
      if (s2.instances.isEmpty) RemoveSession(upd.sessionId, upd.offset)
      else upd.copy(s = s2)
    } else {
      upd
    }
  }

  /**
   * Initialises a new SessionHandler actor. The [[ActorRef]] is named so there
   * will only be 1 instance per proxy server instance.
   *
   * @param cfg
   *   implicit [[AppCfg]] to use
   * @param sys
   *   the untyped / classic [[akka.actor.ActorSystem]] to use
   * @return
   *   a [[SessionHandler.SessionHandlerRef]] containing a reference to the
   *   [[RunnableGraph]] that executes the [[SessionDataConsumer]] stream. And a
   *   typed [[ActorRef]] that understands messages from the defined protocol in
   *   [[SessionHandlerProtocol.Protocol]].
   */
  def init(
      implicit cfg: AppCfg,
      sys: akka.actor.ActorSystem
  ): SessionHandler.SessionHandlerRef = {
    implicit val typedSys = sys.toTyped

    val name = s"session-handler-actor-${cfg.server.serverId.value}"
    log.debug(s"Initialising session handler $name...")
    latestOffset = prepareStateTopic

    val ref = sys.spawn(sessionHandler, name)

    log.debug(s"Starting session state consumer for server id $name...")
    val consumer = new SessionDataConsumer()

    val consumerStream: RunnableGraph[Consumer.Control] =
      consumer.sessionStateSource.to {
        Sink.foreach {
          case upd: UpdateSession =>
            log.trace(s"Consumed internal session protocol message: $upd")
            verifySessionStateRestore(upd)
            if (!stateRestored) ref ! sanitizeSessionOnRestore(upd)
            else ref ! upd

          case rem: RemoveSession =>
            log.trace(s"Consumed internal session protocol message: $rem")
            verifySessionStateRestore(rem)
            ref ! rem

          case ip =>
            log.warn(
              s"Unknown internal session protocol message ${ip.niceClassName}"
            )
        }
      }

    SessionHandler.SessionHandlerRef(consumerStream, ref)
  }

  protected def sessionHandler(implicit cfg: AppCfg): Behavior[Protocol] = {
    Behaviors.setup { implicit ctx =>
      implicit val sys = ctx.system
      implicit val ec  = sys.executionContext
      log.debug("Initialising session data producer...")
      implicit val producer = new SessionDataProducer()
      behavior()
    }
  }

  private[this] def behavior(
      active: ActiveSessions = ActiveSessions()
  )(
      implicit ec: ExecutionContext,
      cfg: AppCfg,
      producer: SessionDataProducer
  ): Behavior[Protocol] = {
    Behaviors.receiveMessage {
      case c: SessionProtocol =>
        log.trace(s"Received a SessionProtocol message [$c]")
        sessionProtocolHandler(active, c)

      case i: InternalSessionProtocol =>
        log.trace(s"Received an InternalProtocol message [$i]")
        internalProtocolHandler(active, i)
    }
  }

  private[this] def sessionProtocolHandler(
      active: ActiveSessions,
      sp: SessionProtocol
  )(
      implicit ec: ExecutionContext,
      cfg: AppCfg,
      producer: SessionDataProducer
  ): Behavior[Protocol] = {
    val serverId = cfg.server.serverId

    sp match {
      case csp: ConsumerSessionProtocol =>
        consumerSessionProtocolHandler(active, csp)

      case psp: ProducerSessionProtocol =>
        producerSessionProtocolHandler(active, psp)

      case SessionHandlerReady(replyTo) =>
        val currentState =
          if (stateRestored) SessionStateRestored()
          else RestoringSessionState()

        replyTo ! currentState
        Behaviors.same

      case StopSessionHandler(replyTo) =>
        Future
          .sequence {
            active.removeInstancesFromServerId(serverId).sessions.map {
              case (sid, s) =>
                producer.publish(s).map { _ =>
                  log.debug(
                    "REMOVE_CLIENT_INSTANCES: clients connected to server " +
                      s"${serverId.value} removed from session ${sid.value}"
                  )
                  Done
                }
            }
          }
          .map(_ => replyTo ! InstancesForServerRemoved())

        log.debug(
          s"STOP: stopping session handler for server ${serverId.value}"
        )
        Behaviors.stopped
    }
  }

  private[this] def consumerSessionProtocolHandler(
      active: ActiveSessions,
      csp: ConsumerSessionProtocol
  )(
      implicit ec: ExecutionContext,
      cfg: AppCfg,
      producer: SessionDataProducer
  ): Behavior[Protocol] = {
    log.trace(s"CLIENT_SESSION: got consumer session protocol message $csp")

    csp match {
      case is: InitConsumerSession =>
        initSessionHandler(active, is) { () =>
          ConsumerSession(is.sessionId, is.groupId, is.maxConnections)
        }

      case ac: AddConsumer        => addClientHandler(active, ac)
      case rc: RemoveConsumer     => removeClientHandler(active, rc)
      case gs: GetConsumerSession => getConsumerSessionHandler(active, gs)
    }
  }

  private[this] def producerSessionProtocolHandler(
      active: ActiveSessions,
      psp: ProducerSessionProtocol
  )(
      implicit ec: ExecutionContext,
      cfg: AppCfg,
      producer: SessionDataProducer
  ): Behavior[Protocol] = {
    log.trace(s"CLIENT_SESSION: got producer session protocol message $psp")

    psp match {
      case is: InitProducerSession =>
        initSessionHandler(active, is) { () =>
          ProducerSession(is.sessionId, is.maxConnections)
        }

      case ap: AddProducer        => addClientHandler(active, ap)
      case rp: RemoveProducer     => removeClientHandler(active, rp)
      case gs: GetProducerSession => getProducerSessionHandler(active, gs)
    }
  }

  private[this] def initSessionHandler[Init <: SessionInitCmd](
      active: ActiveSessions,
      cmd: Init
  )(
      session: () => Session
  )(
      implicit ec: ExecutionContext,
      cfg: AppCfg,
      producer: SessionDataProducer
  ): Behavior[Protocol] = {
    active.find(cmd.sessionId) match {
      case Some(s) =>
        log.debug(
          s"INIT_SESSION: session ${cmd.sessionId.value} already registered."
        )
        cmd.replyTo ! SessionInitialised(s)
        Behaviors.same

      case None =>
        log.debug(
          s"INIT_SESSION: ${cmd.sessionId.value} is not an active session."
        )
        val s = session()
        producer.publish(s).map { _ =>
          log.debug(
            s"INIT_SESSION: session ${cmd.sessionId.value} was registered."
          )
          cmd.replyTo ! SessionInitialised(s)
        }
        active.add(s).map(as => behavior(as)).getOrElse(Behaviors.same)
    }
  }

  private[this] def createClientInstance[Add <: AddClientCmd[_]](
      cmd: Add,
      session: Session
  ): Either[SessionOpResult, ClientInstance] = {
    (session, cmd) match {
      case (_: ConsumerSession, ca: AddConsumer) =>
        Right(ConsumerInstance(ca.fullClientId, ca.serverId))

      case (_: ProducerSession, pa: AddProducer) =>
        Right(ProducerInstance(pa.fullClientId, pa.serverId))

      case (s, a) =>
        log.warn(s"ADD_CLIENT: cmd $a is not compatible with session $s.")
        Left(InstanceTypeForSessionIncorrect(s))
    }
  }

  type AddCmd = AddClientCmd[_ <: FullClientId]
  type RemCmd = RemoveClientCmd[_ <: FullClientId]

  def notAdded[Add <: AddCmd](
      cmd: Add,
      op: SessionOpResult
  ): Behavior[Protocol] = {
    log.debug(
      s"ADD_CLIENT: session op result for session ${cmd.sessionId.value}" +
        s" trying to add client ${cmd.fullClientId.value} on server" +
        s" ${cmd.serverId.value}: ${op.asString}"
    )
    cmd.replyTo ! op
    Behaviors.same
  }

  private[this] def addClientHandler[Cmd <: AddCmd](
      active: ActiveSessions,
      cmd: Cmd
  )(
      implicit ec: ExecutionContext,
      producer: SessionDataProducer
  ): Behavior[Protocol] = {
    log.debug(
      s"ADD_CLIENT: adding client instance ${cmd.fullClientId.value} " +
        s"connected to server ${cmd.serverId.value} to " +
        s"session ${cmd.sessionId.value}..."
    )

    active.find(cmd.sessionId) match {
      case None =>
        log.warn(
          s"ADD_CLIENT: session ${cmd.sessionId.value} was not found. " +
            s"client ID ${cmd.fullClientId} will not be added."
        )
        cmd.replyTo ! SessionNotFound(cmd.sessionId)
        Behaviors.same

      case Some(session) =>
        log.trace("ADD_CLIENT: creating internal client session...")
        createClientInstance(cmd, session) match {
          case Right(instance) =>
            val addRes = instance match {
              case prodInst: ProducerInstance => session.addInstance(prodInst)
              case consInst: ConsumerInstance => session.addInstance(consInst)
            }

            addRes match {
              case added @ InstanceAdded(s) =>
                log.trace("ADD_CLIENT: publishing updated session...")
                producer.publish(s).map { _ =>
                  log.debug(
                    s"ADD_CLIENT: client instance ${cmd.fullClientId.value} " +
                      s"added to session ${cmd.sessionId.value} from server " +
                      s"${cmd.serverId.value}"
                  )
                  cmd.replyTo ! added
                }
                Behaviors.same

              case op => notAdded(cmd, op)
            }

          case Left(invalidType) => notAdded(cmd, invalidType)
        }
    }
  }

  private[this] def removeClientHandler[Cmd <: RemCmd](
      active: ActiveSessions,
      cmd: Cmd
  )(
      implicit ec: ExecutionContext,
      producer: SessionDataProducer
  ): Behavior[Protocol] = {
    log.debug(
      s"REMOVE_CLIENT: remove client instance ${cmd.fullClientId} from " +
        s"session ${cmd.sessionId.value} on server..."
    )
    active.find(cmd.sessionId) match {
      case None =>
        log.debug(
          s"REMOVE_CLIENT: session ${cmd.sessionId.value} was not found"
        )
        cmd.replyTo ! SessionNotFound(cmd.sessionId)
        Behaviors.same

      case Some(session) =>
        session.removeInstance(cmd.fullClientId) match {
          case removed @ InstanceRemoved(s) =>
            producer.publish(s).foreach { _ =>
              log.debug(
                s"REMOVE_CLIENT: client ${cmd.fullClientId.value} removed " +
                  s"from session ${cmd.sessionId.value}"
              )
              cmd.replyTo ! removed
            }
            Behaviors.same

          case notRemoved =>
            log.debug(
              "REMOVE_CLIENT: session op result " +
                s"for ${cmd.sessionId.value}: ${notRemoved.asString}"
            )
            cmd.replyTo ! notRemoved
            Behaviors.same
        }
    }
  }

  private[this] def getConsumerSessionHandler(
      active: ActiveSessions,
      gs: GetConsumerSession
  ): Behavior[Protocol] = {
    gs.replyTo ! active.find(gs.sessionId).flatMap {
      case cs: ConsumerSession => Option(cs)
      case _                   => None
    }
    Behaviors.same
  }

  private[this] def getProducerSessionHandler(
      active: ActiveSessions,
      gs: GetProducerSession
  ): Behavior[Protocol] = {
    gs.replyTo ! active.find(gs.sessionId).flatMap {
      case ps: ProducerSession => Option(ps)
      case _                   => None
    }
    Behaviors.same
  }

  private[this] def internalProtocolHandler(
      active: ActiveSessions,
      ip: InternalSessionProtocol
  )(
      implicit ec: ExecutionContext,
      cfg: AppCfg,
      producer: SessionDataProducer
  ): Behavior[Protocol] = ip match {
    case UpdateSession(sessionId, s, _) =>
      log.debug(s"INTERNAL: Updating session ${sessionId.value}...")
      nextOrSameBehavior(active.updateSession(sessionId, s))(behavior)

    case RemoveSession(sessionId, _) =>
      log.debug(s"INTERNAL: Removing session ${sessionId.value}..")
      nextOrSameBehavior(active.removeSession(sessionId))(behavior)
  }
}
