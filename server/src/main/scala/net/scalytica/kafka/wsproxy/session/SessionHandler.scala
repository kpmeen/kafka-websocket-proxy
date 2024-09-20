package net.scalytica.kafka.wsproxy.session

import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.Future

import net.scalytica.kafka.wsproxy.admin.WsKafkaAdminClient
import net.scalytica.kafka.wsproxy.config.Configuration.AppCfg
import net.scalytica.kafka.wsproxy.logging.WithProxyLogger
import net.scalytica.kafka.wsproxy.models.FullClientId
import net.scalytica.kafka.wsproxy.session.SessionHandlerProtocol._

import org.apache.pekko.Done
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.adapter._
import org.apache.pekko.kafka.scaladsl.Consumer
import org.apache.pekko.stream.scaladsl.RunnableGraph
import org.apache.pekko.stream.scaladsl.Sink

object SessionHandler extends SessionHandler

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
      behavior: ActiveSessions => Behavior[SessionProtocol]
  ): Behavior[SessionProtocol] = {
    either match {
      case Left(_)   => Behaviors.same
      case Right(ns) => behavior(ns)
    }
  }

  private[this] def prepareTopic(implicit cfg: AppCfg): Long = {
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
    } catch {
      case ex: Throwable =>
        log.error(
          "A fatal error occurred while attempting to init and verify the" +
            " session state topic. Server will terminate",
          ex
        )
        System.exit(1)
        // FIXME: Dirty hack to align types
        -1L
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
   * Initialises a new SessionHandler actor. The
   * [[org.apache.pekko.actor.typed.ActorRef]] is named so there will only be 1
   * instance per proxy server instance.
   *
   * @param cfg
   *   implicit [[AppCfg]] to use
   * @param sys
   *   the untyped / classic [[org.apache.pekko.actor.ActorSystem]] to use
   * @return
   *   a [[SessionHandlerRef]] containing a reference to the [[RunnableGraph]]
   *   that executes the [[SessionDataConsumer]] stream. And a typed
   *   [[org.apache.pekko.actor.typed.ActorRef]] that understands messages from
   *   the defined protocol in [[SessionHandlerProtocol.SessionProtocol]].
   */
  def init(
      implicit cfg: AppCfg,
      sys: org.apache.pekko.actor.ActorSystem
  ): SessionHandlerRef = {
    implicit val typedSys: ActorSystem[_] = sys.toTyped

    val name = s"session-handler-actor-${cfg.server.serverId.value}"
    log.debug(s"Initialising session handler $name...")
    latestOffset = prepareTopic

    val ref = sys.spawn(sessionHandler, name)

    log.debug(s"Starting session state consumer with identifier $name...")
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

    SessionHandlerRef(consumerStream, ref)
  }

  protected def sessionHandler(
      implicit cfg: AppCfg
  ): Behavior[SessionProtocol] = {
    Behaviors.setup { implicit ctx =>
      implicit val sys: ActorSystem[_]          = ctx.system
      implicit val ec: ExecutionContextExecutor = sys.executionContext
      log.debug("Initialising session data producer...")
      implicit val producer: SessionDataProducer = new SessionDataProducer()
      behavior()
    }
  }

  private[this] def behavior(
      active: ActiveSessions = ActiveSessions()
  )(
      implicit ec: ExecutionContext,
      cfg: AppCfg,
      producer: SessionDataProducer
  ): Behavior[SessionProtocol] = {
    Behaviors.receiveMessage {
      case c: ClientSessionProtocol =>
        log.trace(s"Received a SessionProtocol message [$c]")
        sessionProtocolHandler(active, c)

      case i: InternalSessionProtocol =>
        log.trace(s"Received an InternalProtocol message [$i]")
        internalProtocolHandler(active, i)
    }
  }

  private[this] def sessionProtocolHandler(
      active: ActiveSessions,
      sp: ClientSessionProtocol
  )(
      implicit ec: ExecutionContext,
      cfg: AppCfg,
      producer: SessionDataProducer
  ): Behavior[SessionProtocol] = {
    val serverId = cfg.server.serverId

    sp match {
      case csp: ConsumerSessionProtocol =>
        consumerSessionProtocolHandler(active, csp)

      case psp: ProducerSessionProtocol =>
        producerSessionProtocolHandler(active, psp)

      case CheckSessionHandlerReady(replyTo) =>
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
  ): Behavior[SessionProtocol] = {
    log.trace(s"CLIENT_SESSION: got consumer session protocol message $csp")

    csp match {
      case is: InitConsumerSession =>
        initSessionHandler(active, is) { () =>
          ConsumerSession(is.sessionId, is.groupId, is.maxConnections)
        }

      case ac: AddConsumer           => addClientHandler(active, ac)
      case rc: RemoveConsumer        => removeClientHandler(active, rc)
      case uc: UpdateConsumerSession => updateConsumerSessionHandler(active, uc)
      case gs: GetConsumerSession    => getConsumerSessionHandler(active, gs)
    }
  }

  private[this] def producerSessionProtocolHandler(
      active: ActiveSessions,
      psp: ProducerSessionProtocol
  )(
      implicit ec: ExecutionContext,
      cfg: AppCfg,
      producer: SessionDataProducer
  ): Behavior[SessionProtocol] = {
    log.trace(s"CLIENT_SESSION: got producer session protocol message $psp")

    psp match {
      case is: InitProducerSession =>
        initSessionHandler(active, is) { () =>
          ProducerSession(is.sessionId, is.maxConnections)
        }

      case ap: AddProducer           => addClientHandler(active, ap)
      case rp: RemoveProducer        => removeClientHandler(active, rp)
      case up: UpdateProducerSession => updateProducerSessionHandler(active, up)
      case gs: GetProducerSession    => getProducerSessionHandler(active, gs)
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
  ): Behavior[SessionProtocol] = {
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

  private[this] type AddCmd = AddClientCmd[_ <: FullClientId]
  private[this] type RemCmd = RemoveClientCmd[_ <: FullClientId]

  private[this] def notAdded[Add <: AddCmd](
      cmd: Add,
      op: SessionOpResult
  ): Behavior[SessionProtocol] = {
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
  ): Behavior[SessionProtocol] = {
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
  ): Behavior[SessionProtocol] = {
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

  private[this] def updateConsumerSessionHandler(
      active: ActiveSessions,
      ucs: UpdateConsumerSession
  )(
      implicit ec: ExecutionContext,
      cfg: AppCfg,
      producer: SessionDataProducer
  ): Behavior[SessionProtocol] = {
    active
      .find(ucs.sessionId)
      .flatMap { s =>
        val upd = s.updateConfig(ucs.cfg)
        active.updateSession(ucs.sessionId, upd).toOption.map(a => upd -> a)
      }
      .map { case (s, a) =>
        ucs.replyTo ! SessionUpdated(s)
        behavior(a)
      }
      .getOrElse {
        ucs.replyTo ! SessionNotFound(ucs.sessionId)
        Behaviors.same
      }
  }

  private[this] def updateProducerSessionHandler(
      active: ActiveSessions,
      ups: UpdateProducerSession
  )(
      implicit ec: ExecutionContext,
      cfg: AppCfg,
      producer: SessionDataProducer
  ): Behavior[SessionProtocol] = {
    active
      .find(ups.sessionId)
      .flatMap { s =>
        val upd = s.updateConfig(ups.cfg)
        active.updateSession(ups.sessionId, upd).toOption.map(a => upd -> a)
      }
      .map { case (s, a) =>
        ups.replyTo ! SessionUpdated(s)
        behavior(a)
      }
      .getOrElse {
        ups.replyTo ! SessionNotFound(ups.sessionId)
        Behaviors.same
      }
  }

  private[this] def getConsumerSessionHandler(
      active: ActiveSessions,
      gs: GetConsumerSession
  ): Behavior[SessionProtocol] = {
    gs.replyTo ! active.find(gs.sessionId).flatMap {
      case cs: ConsumerSession => Option(cs)
      case _                   => None
    }
    Behaviors.same
  }

  private[this] def getProducerSessionHandler(
      active: ActiveSessions,
      gs: GetProducerSession
  ): Behavior[SessionProtocol] = {
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
  ): Behavior[SessionProtocol] = ip match {
    case UpdateSession(sessionId, s, _) =>
      log.debug(s"INTERNAL: Updating session ${sessionId.value}...")
      nextOrSameBehavior(active.updateSession(sessionId, s))(behavior)

    case RemoveSession(sessionId, _) =>
      log.debug(s"INTERNAL: Removing session ${sessionId.value}..")
      nextOrSameBehavior(active.removeSession(sessionId))(behavior)
  }
}
