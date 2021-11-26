package net.scalytica.kafka.wsproxy.session

import akka.Done
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.{ActorRef, Behavior, Scheduler}
import akka.kafka.scaladsl.Consumer
import akka.stream.scaladsl.{RunnableGraph, Sink}
import akka.util.Timeout
import net.scalytica.kafka.wsproxy.admin.WsKafkaAdminClient
import net.scalytica.kafka.wsproxy.config.Configuration.AppCfg
import net.scalytica.kafka.wsproxy.logging.WithProxyLogger
import net.scalytica.kafka.wsproxy.models.{WsClientId, WsGroupId, WsServerId}
import net.scalytica.kafka.wsproxy.session.SessionHandlerProtocol._

import java.util.concurrent.TimeoutException
import scala.concurrent.{ExecutionContext, Future}

/**
 * Encapsulates the communication protocol to use with the [[SessionHandler]]
 * Actor. It's split into two distinct sub-protocols. Where one is intended to
 * be used by the main application flow, and the other is to manipulate and
 * update the active sessions state.
 */
object SessionHandlerProtocol {

  /** Main protocol to be used by the proxy service */
  sealed trait Protocol

  /**
   * Protocol to use for session related behaviour from outside the session
   * handler.
   */
  sealed trait SessionProtocol extends Protocol
  /** Consumer session specific protocol */
  sealed trait ConsumerSessionProtocol extends SessionProtocol
  /** Producer session specific protocol */
  sealed trait ProducerSessionProtocol extends SessionProtocol

  sealed trait SessionInitCmd {
    val sessionId: SessionId
    val maxConnections: Int
    val replyTo: ActorRef[SessionOpResult]
  }

  sealed trait AddClientCmd {
    val sessionId: SessionId
    val clientId: WsClientId
    val serverId: WsServerId
    val replyTo: ActorRef[SessionOpResult]
  }

  sealed trait RemoveClientCmd {
    val sessionId: SessionId
    val clientId: WsClientId
    val replyTo: ActorRef[SessionOpResult]
  }

  /**
   * Command message to terminate a given Session
   *
   * @param replyTo
   *   the [[ActorRef]] that sent the command
   */
  final case class StopSessionHandler(
      replyTo: ActorRef[SessionOpResult]
  ) extends SessionProtocol

  // Consumer session specific commands
  final case class InitConsumerSession(
      sessionId: SessionId,
      groupId: WsGroupId,
      maxConnections: Int,
      replyTo: ActorRef[SessionOpResult]
  ) extends ConsumerSessionProtocol
      with SessionInitCmd

  final case class AddConsumer(
      sessionId: SessionId,
      groupId: WsGroupId,
      clientId: WsClientId,
      serverId: WsServerId,
      replyTo: ActorRef[SessionOpResult]
  ) extends ConsumerSessionProtocol
      with AddClientCmd

  final case class RemoveConsumer(
      sessionId: SessionId,
      groupId: WsGroupId,
      clientId: WsClientId,
      replyTo: ActorRef[SessionOpResult]
  ) extends ConsumerSessionProtocol
      with RemoveClientCmd

  final case class GetConsumerSession(
      sessionId: SessionId,
      replyTo: ActorRef[Option[ConsumerSession]]
  ) extends ConsumerSessionProtocol

  // Producer session specific commands

  final case class InitProducerSession(
      sessionId: SessionId,
      maxConnections: Int,
      replyTo: ActorRef[SessionOpResult]
  ) extends ProducerSessionProtocol
      with SessionInitCmd

  final case class AddProducer(
      sessionId: SessionId,
      clientId: WsClientId,
      serverId: WsServerId,
      replyTo: ActorRef[SessionOpResult]
  ) extends ProducerSessionProtocol
      with AddClientCmd

  final case class RemoveProducer(
      sessionId: SessionId,
      clientId: WsClientId,
      replyTo: ActorRef[SessionOpResult]
  ) extends ProducerSessionProtocol
      with RemoveClientCmd

  final case class GetProducerSession(
      sessionId: SessionId,
      replyTo: ActorRef[Option[ProducerSession]]
  ) extends ProducerSessionProtocol

  /** Sub-protocol only to be used by the Kafka consumer */
  sealed private[session] trait InternalSessionProtocol extends Protocol

  final private[session] case class UpdateSession(
      sessionId: SessionId,
      s: Session
  ) extends InternalSessionProtocol

  final private[session] case class RemoveSession(
      sessionId: SessionId
  ) extends InternalSessionProtocol
}

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
        groupId: Option[WsGroupId],
        clientId: Option[WsClientId],
        serverId: Option[WsServerId]
    )(timeoutResponse: String => T)(throwable: Throwable): Future[T] = {
      val op = throwable.getStackTrace
        .dropWhile(ste => !ste.getClassName.equals(getClass.getName))
        .headOption
        .map(_.getMethodName)
        .getOrElse("unknown")
      val cid = clientId.map(c => s"for $c").getOrElse("")
      val sid = serverId.map(s => s"on $s").getOrElse("")
      val gid = groupId.map(g => s"in $g").getOrElse("")

      throwable match {
        case t: TimeoutException =>
          logger.debug(s"Timeout calling $op $cid $gid in $sessionId $sid", t)
          Future.successful(timeoutResponse(t.getMessage))

        case t: Throwable =>
          logger.warn(
            s"Unhandled error calling $op $cid $gid in $sessionId $sid",
            t
          )
          throw t
      }
    }

    private[this] def handleClientSessionOpError(
        sessionId: SessionId,
        groupId: Option[WsGroupId] = None,
        clientId: Option[WsClientId] = None,
        serverId: Option[WsServerId] = None
    )(throwable: Throwable): Future[SessionOpResult] = {
      handleClientError[SessionOpResult](
        sessionId = sessionId,
        groupId = groupId,
        clientId = clientId,
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

    def initProducerSession(sessionId: SessionId, maxClients: Int)(
        implicit ec: ExecutionContext,
        timeout: Timeout,
        scheduler: Scheduler
    ): Future[SessionOpResult] = {
      sh.ask[SessionOpResult] { ref =>
        SessionHandlerProtocol.InitProducerSession(
          sessionId = sessionId,
          maxConnections = maxClients,
          replyTo = ref
        )
      }.recoverWith(handleClientSessionOpError(sessionId)(_))
    }

    def addConsumer(
        groupId: WsGroupId,
        clientId: WsClientId,
        serverId: WsServerId
    )(
        implicit ec: ExecutionContext,
        timeout: Timeout,
        scheduler: Scheduler
    ): Future[SessionOpResult] = {
      val sid = SessionId(groupId)
      sh.ask[SessionOpResult] { ref =>
        SessionHandlerProtocol.AddConsumer(
          sessionId = sid,
          groupId = groupId,
          clientId = clientId,
          serverId = serverId,
          replyTo = ref
        )
      }.recoverWith(
        handleClientSessionOpError(
          sessionId = sid,
          groupId = Option(groupId),
          clientId = Option(clientId),
          serverId = Option(serverId)
        )(_)
      )
    }

    def addProducer(
        sessionId: SessionId,
        clientId: WsClientId,
        serverId: WsServerId
    )(
        implicit ec: ExecutionContext,
        timeout: Timeout,
        scheduler: Scheduler
    ): Future[SessionOpResult] = {
      sh.ask[SessionOpResult] { ref =>
        SessionHandlerProtocol.AddProducer(
          sessionId = sessionId,
          clientId = clientId,
          serverId = serverId,
          replyTo = ref
        )
      }.recoverWith(
        handleClientSessionOpError(
          sessionId = sessionId,
          clientId = Option(clientId),
          serverId = Option(serverId)
        )(_)
      )
    }

    def removeConsumer(
        groupId: WsGroupId,
        clientId: WsClientId,
        serverId: WsServerId
    )(
        implicit ec: ExecutionContext,
        timeout: Timeout,
        scheduler: Scheduler
    ): Future[SessionOpResult] = {
      val sid = SessionId(groupId)

      sh.ask[SessionOpResult] { ref =>
        SessionHandlerProtocol.RemoveConsumer(sid, groupId, clientId, ref)
      }.recoverWith(
        handleClientSessionOpError(
          sessionId = sid,
          groupId = Option(groupId),
          clientId = Option(clientId),
          serverId = Option(serverId)
        )(_)
      )
    }

    def removeProducer(
        clientId: WsClientId,
        serverId: WsServerId
    )(
        implicit ec: ExecutionContext,
        timeout: Timeout,
        scheduler: Scheduler
    ): Future[SessionOpResult] = {
      removeProducer(SessionId(clientId), clientId, serverId)
    }

    def removeProducer(
        sessionId: SessionId,
        clientId: WsClientId,
        serverId: WsServerId
    )(
        implicit ec: ExecutionContext,
        timeout: Timeout,
        scheduler: Scheduler
    ): Future[SessionOpResult] = {
      sh.ask[SessionOpResult] { ref =>
        SessionHandlerProtocol.RemoveProducer(sessionId, clientId, ref)
      }.recoverWith(
        handleClientSessionOpError(
          sessionId = sessionId,
          clientId = Option(clientId),
          serverId = Option(serverId)
        )(_)
      )
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
          logger.debug("Timeout calling sessionShutdown()", t)
          Future.successful(IncompleteOperation(t.getMessage))

        case t: Throwable =>
          logger.warn("An unknown error occurred calling sessionShutdown()", t)
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
          groupId = Option(groupId),
          clientId = None,
          serverId = None
        ) { _ =>
          None
        }(_)
      )
    }

    def getProducerSession(sessionId: SessionId)(
        implicit ec: ExecutionContext,
        timeout: Timeout,
        scheduler: Scheduler
    ): Future[Option[ProducerSession]] = {
      sh.ask[Option[ProducerSession]] { ref =>
        SessionHandlerProtocol.GetProducerSession(sessionId, ref)
      }.recoverWith(
        handleClientError[Option[ProducerSession]](
          sessionId = sessionId,
          groupId = None,
          clientId = None,
          serverId = None
        ) { _ =>
          None
        }(_)
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
  private[this] def nextOrSame(
      either: Either[String, ActiveSessions]
  )(
      behavior: ActiveSessions => Behavior[Protocol]
  ): Behavior[Protocol] = {
    either match {
      case Left(_)   => Behaviors.same
      case Right(ns) => behavior(ns)
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
    logger.debug(s"Initialising session handler $name...")

    val admin = new WsKafkaAdminClient(cfg)
    try {
      val ready = admin.clusterReady
      if (ready) admin.initSessionStateTopic()
      else {
        logger.error("Could not reach Kafka cluster. Terminating application!")
        System.exit(1)
      }
    } finally {
      admin.close()
    }

    val ref = sys.spawn(sessionHandler, name)

    logger.debug(s"Starting session state consumer for server id $name...")

    val consumer = new SessionDataConsumer()

    val consumerStream: RunnableGraph[Consumer.Control] =
      consumer.sessionStateSource.to {
        Sink.foreach {
          case ip: InternalSessionProtocol =>
            logger.debug(s"Consumed internal session protocol message: $ip")
            ref ! ip

          case _ =>
            logger.debug(s"No previous sessions found...")
            ()
        }
      }

    SessionHandler.SessionHandlerRef(consumerStream, ref)
  }

  protected def sessionHandler(implicit cfg: AppCfg): Behavior[Protocol] = {
    Behaviors.setup { implicit ctx =>
      implicit val sys = ctx.system
      implicit val ec  = sys.executionContext

      logger.debug("Initialising session data producer...")
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
        logger.trace(s"Received a SessionProtocol message [$c]")
        sessionProtocolHandler(active, c)

      case i: InternalSessionProtocol =>
        logger.trace(s"Received an InternalProtocol message [$i]")
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

      case StopSessionHandler(replyTo) =>
        Future
          .sequence {
            active.removeInstancesFromServerId(serverId).sessions.map {
              case (sid, s) =>
                producer.publish(s).map { _ =>
                  logger.debug(
                    "REMOVE_CLIENT_INSTANCES: clients connected to server " +
                      s"${serverId.value} removed from session ${sid.value}"
                  )
                  Done
                }
            }
          }
          .map { _ =>
            replyTo ! InstancesForServerRemoved()
          }
        logger.debug(
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
    logger.trace(s"CLIENT_SESSION: got consumer session protocol message $csp")

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
    logger.trace(s"CLIENT_SESSION: got producer session protocol message $psp")

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
        logger.debug(
          s"INIT_SESSION: session ${cmd.sessionId.value} already registered."
        )
        cmd.replyTo ! SessionInitialised(s)
        Behaviors.same

      case None =>
        logger.debug(
          s"INIT_SESSION: ${cmd.sessionId.value} is not an active session."
        )
        val s = session()
        producer.publish(s).map { _ =>
          logger.debug(
            s"INIT_SESSION: session ${cmd.sessionId.value} was registered."
          )
          cmd.replyTo ! SessionInitialised(s)
        }
        active.add(s).map(as => behavior(as)).getOrElse(Behaviors.same)
    }
  }

  private[this] def createClientInstance[Add <: AddClientCmd](
      cmd: Add,
      session: Session
  ): Either[SessionOpResult, ClientInstance] = {
    (session, cmd) match {
      case (cs: ConsumerSession, ca: AddConsumer) =>
        Right(ConsumerInstance(ca.clientId, cs.groupId, ca.serverId))

      case (_: ProducerSession, pa: AddProducer) =>
        Right(ProducerInstance(pa.clientId, pa.serverId))

      case (s, a) =>
        logger.warn(s"ADD_CLIENT: cmd $a is not compatible with session $s.")
        Left(InstanceTypeForSessionIncorrect(s))
    }
  }

  def notAdded[Add <: AddClientCmd](
      cmd: Add,
      op: SessionOpResult
  ): Behavior[Protocol] = {
    logger.debug(
      s"ADD_CLIENT: session op result for session ${cmd.sessionId.value}" +
        s" trying to add client ${cmd.clientId.value} on server" +
        s" ${cmd.serverId.value}: ${op.asString}"
    )
    cmd.replyTo ! op
    Behaviors.same
  }

  private[this] def addClientHandler[Add <: AddClientCmd](
      active: ActiveSessions,
      cmd: Add
  )(
      implicit ec: ExecutionContext,
      producer: SessionDataProducer
  ): Behavior[Protocol] = {
    logger.debug(
      s"ADD_CLIENT: adding client instance ${cmd.clientId.value} connected " +
        s"to server ${cmd.serverId.value} to session ${cmd.sessionId.value}..."
    )

    active.find(cmd.sessionId) match {
      case None =>
        logger.warn(
          s"ADD_CLIENT: session ${cmd.sessionId.value} was not found. " +
            s"client ID ${cmd.clientId} will not be added."
        )
        cmd.replyTo ! SessionNotFound(cmd.sessionId)
        Behaviors.same

      case Some(session) =>
        logger.trace("ADD_CLIENT: creating internal client session...")
        createClientInstance(cmd, session) match {
          case Right(instance) =>
            session.addInstance(instance) match {
              case added @ InstanceAdded(s) =>
                logger.trace("ADD_CLIENT: publishing updated session...")
                producer.publish(s).map { _ =>
                  logger.debug(
                    s"ADD_CLIENT: client instance ${cmd.clientId.value} added" +
                      s"to session ${cmd.sessionId.value} from server " +
                      s"${cmd.serverId.value}"
                  )
                  cmd.replyTo ! added
                }
                Behaviors.same

              case op =>
                notAdded(cmd, op)
            }

          case Left(invalidType) =>
            notAdded(cmd, invalidType)
        }
    }
  }

  private[this] def removeClientHandler[Remove <: RemoveClientCmd](
      active: ActiveSessions,
      cmd: Remove
  )(
      implicit ec: ExecutionContext,
      producer: SessionDataProducer
  ): Behavior[Protocol] = {
    logger.debug(
      s"REMOVE_CLIENT: remove client instance ${cmd.clientId.value} from " +
        s"session ${cmd.sessionId.value} on server..."
    )
    active.find(cmd.sessionId) match {
      case None =>
        logger.debug(
          s"REMOVE_CLIENT: session ${cmd.sessionId.value} was not found"
        )
        cmd.replyTo ! SessionNotFound(cmd.sessionId)
        Behaviors.same

      case Some(session) =>
        session.removeInstance(cmd.clientId) match {
          case removed @ InstanceRemoved(s) =>
            producer.publish(s).foreach { _ =>
              logger.debug(
                s"REMOVE_CLIENT: client ${cmd.clientId.value} removed " +
                  s"from session ${cmd.sessionId.value}"
              )
              cmd.replyTo ! removed
            }
            Behaviors.same

          case notRemoved =>
            logger.debug(
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
    case UpdateSession(sessionId, s) =>
      logger.debug(s"INTERNAL: Updating session ${sessionId.value}...")
      nextOrSame(active.updateSession(sessionId, s))(behavior)

    case RemoveSession(sessionId) =>
      logger.debug(s"INTERNAL: Removing session ${sessionId.value}..")
      nextOrSame(active.removeSession(sessionId))(behavior)
  }

}
