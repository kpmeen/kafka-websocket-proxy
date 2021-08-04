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
import net.scalytica.kafka.wsproxy.jmx.WsProxyJmxRegistrar
import net.scalytica.kafka.wsproxy.logging.WithProxyLogger
import net.scalytica.kafka.wsproxy.models.{WsClientId, WsGroupId, WsServerId}
import net.scalytica.kafka.wsproxy.session.Session.{
  IncompleteOperation,
  SessionOpResult
}
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

  sealed trait ClientSessionProtocol extends Protocol

  case class InitSession(
      groupId: WsGroupId,
      consumerLimit: Int,
      replyTo: ActorRef[Session.SessionOpResult]
  ) extends ClientSessionProtocol

  case class AddConsumer(
      groupId: WsGroupId,
      consumerId: WsClientId,
      serverId: WsServerId,
      replyTo: ActorRef[Session.SessionOpResult]
  ) extends ClientSessionProtocol

  case class RemoveConsumer(
      groupId: WsGroupId,
      consumerId: WsClientId,
      replyTo: ActorRef[Session.SessionOpResult]
  ) extends ClientSessionProtocol

  case class GetSession(
      groupId: WsGroupId,
      replyTo: ActorRef[Option[Session]]
  ) extends ClientSessionProtocol

  case class Stop(replyTo: ActorRef[Session.SessionOpResult])
      extends ClientSessionProtocol

  /** Sub-protocol only to be used by the Kafka consumer */
  sealed private[session] trait InternalProtocol extends Protocol

  private[session] case class UpdateSession(
      groupId: WsGroupId,
      s: Session
  ) extends InternalProtocol

  private[session] case class RemoveSession(
      groupId: WsGroupId
  ) extends InternalProtocol
}

object SessionHandler extends SessionHandler {

  case class SessionHandlerRef(
      stream: RunnableGraph[Consumer.Control],
      shRef: ActorRef[SessionHandlerProtocol.Protocol]
  )

  implicit class SessionHandlerOpExtensions(
      sh: ActorRef[SessionHandlerProtocol.Protocol]
  ) {

    private[this] def handleError[T](
        groupId: WsGroupId,
        clientId: Option[WsClientId] = None,
        serverId: Option[WsServerId] = None
    )(timeoutResponse: String => T)(throwable: Throwable): Future[T] = {
      val op = throwable.getStackTrace
        .dropWhile(ste => !ste.getClassName.equals(getClass.getName))
        .headOption
        .map(_.getMethodName)
        .getOrElse("unknown")
      val cid = clientId.map(c => s"on $c").getOrElse("")
      val sid = serverId.map(s => s"on $s").getOrElse("")

      throwable match {
        case t: TimeoutException =>
          logger.debug(s"Timeout calling $op $cid in $groupId $sid", t)
          Future.successful(timeoutResponse(t.getMessage))

        case t: Throwable =>
          logger.warn(
            s"An unknown error occurred calling $op $cid in $groupId $sid",
            t
          )
          throw t
      }
    }

    private[this] def handleSessionOpError(
        groupId: WsGroupId,
        clientId: WsClientId,
        serverId: WsServerId
    )(throwable: Throwable): Future[SessionOpResult] = {
      handleSessionOpError(groupId, Some(clientId), Some(serverId))(throwable)
    }

    private[this] def handleSessionOpError(
        groupId: WsGroupId,
        clientId: Option[WsClientId] = None,
        serverId: Option[WsServerId] = None
    )(throwable: Throwable): Future[SessionOpResult] = {
      handleError[SessionOpResult](
        groupId = groupId,
        clientId = clientId,
        serverId = serverId
      )(reason => IncompleteOperation(reason))(throwable)
    }

    def initSession(groupId: WsGroupId, consumerLimit: Int)(
        implicit ec: ExecutionContext,
        timeout: Timeout,
        scheduler: Scheduler
    ): Future[SessionOpResult] = {
      sh.ask[SessionOpResult] { ref =>
        SessionHandlerProtocol.InitSession(
          groupId = groupId,
          consumerLimit = consumerLimit,
          replyTo = ref
        )
      }.recoverWith(handleSessionOpError(groupId)(_))
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
      def add() = sh
        .ask[SessionOpResult] { ref =>
          SessionHandlerProtocol.AddConsumer(
            groupId = groupId,
            consumerId = clientId,
            serverId = serverId,
            replyTo = ref
          )
        }
        .recoverWith(handleSessionOpError(groupId, clientId, serverId)(_))

      WsProxyJmxRegistrar.findConsumerClientMBean(clientId, groupId) match {
        case Some(_) =>
          // Let the add logic pan out as normal, and fail if the consumer
          // is already registered.
          add()

        case None =>
          // There is no MBean registered, meaning there should be no consumer
          // registered in the session. Just in case, we trigger a removal to
          // avoid not being able to connect the consumer after an unexpected
          // disconnect.
          for {
            _      <- removeConsumer(groupId, clientId, serverId)
            addRes <- add()
          } yield addRes

      }
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
      sh.ask[SessionOpResult] { ref =>
        SessionHandlerProtocol.RemoveConsumer(groupId, clientId, ref)
      }.recoverWith(handleSessionOpError(groupId, clientId, serverId)(_))
    }

    def sessionShutdown()(
        implicit ec: ExecutionContext,
        timeout: Timeout,
        scheduler: Scheduler
    ): Future[SessionOpResult] = {
      sh.ask[SessionOpResult] { ref =>
        SessionHandlerProtocol.Stop(ref)
      }.recoverWith {
        case t: TimeoutException =>
          logger.debug("Timeout calling sessionShutdown()", t)
          Future.successful(IncompleteOperation(t.getMessage))

        case t: Throwable =>
          logger.warn("An unknown error occurred calling sessionShutdown()", t)
          throw t
      }
    }

    def getSession(groupId: WsGroupId)(
        implicit ec: ExecutionContext,
        timeout: Timeout,
        scheduler: Scheduler
    ): Future[Option[Session]] = {
      sh.ask[Option[Session]] { ref =>
        SessionHandlerProtocol.GetSession(groupId, ref)
      }.recoverWith(handleError[Option[Session]](groupId)(_ => None)(_))
    }

  }

}

/**
 * Logic for building a clustered session handler using Kafka.
 *
 * The idea is to use a compacted Kafka topic as the source of truth for
 * consumer group session state. Each instance of the kafka-websocket-proxy will
 * have a single actor that can read and write from/to that topic. The topic
 * data should always be the up-to-date version of the state for all proxy
 * instances, and we can therefore implement some nice features on top of that
 * capability.
 *
 *   - Keeping track of number of sockets per consumer group
 *   - Keeping track of which consumers are part of the consumer group
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
          case ip: InternalProtocol =>
            logger.debug(s"Consumed message: $ip")
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
      case c: ClientSessionProtocol =>
        logger.trace(s"Received a ClientSessionProtocol message [$c]")
        clientSessionProtocolHandler(active, c)

      case i: InternalProtocol =>
        logger.trace(s"Received an InternalProtocol message [$i]")
        internalProtocolHandler(active, i)
    }
  }

  private[this] def clientSessionProtocolHandler(
      active: ActiveSessions,
      csp: ClientSessionProtocol
  )(
      implicit ec: ExecutionContext,
      cfg: AppCfg,
      producer: SessionDataProducer
  ): Behavior[Protocol] = {
    val serverId = cfg.server.serverId

    logger.trace(s"CLIENT_SESSION: got client session protocol message $csp")

    csp match {
      case is: InitSession    => initSessionHandler(active, is)
      case ac: AddConsumer    => addConsumerHandler(active, ac)
      case rc: RemoveConsumer => removeConsumerHandler(active, rc)
      case gs: GetSession     => getSessionHandler(active, gs)
      case Stop(replyTo) =>
        Future
          .sequence {
            active.removeConsumersFromServerId(serverId).sessions.map {
              case (gid, s) =>
                producer.publish(s).map { _ =>
                  logger.debug(
                    "REMOVE_CONSUMER: consumers connected to server " +
                      s"${serverId.value} removed from session ${gid.value}"
                  )
                  Done
                }
            }
          }
          .map { _ =>
            replyTo ! Session.ConsumersForServerRemoved
          }
        logger.debug(
          s"STOP: stopping session handler for server ${serverId.value}"
        )
        Behaviors.stopped
    }
  }

  private[this] def initSessionHandler(
      active: ActiveSessions,
      is: InitSession
  )(
      implicit ec: ExecutionContext,
      cfg: AppCfg,
      producer: SessionDataProducer
  ): Behavior[Protocol] = {
    logger.debug(
      s"INIT_SESSION: initialise session ${is.groupId.value} " +
        s"with limit ${is.consumerLimit}"
    )
    active.find(is.groupId) match {
      case Some(s) =>
        logger.debug(
          s"INIT_SESSION: session ${is.groupId.value} already initialised."
        )
        is.replyTo ! Session.SessionInitialised(s)
        Behaviors.same

      case None =>
        logger.debug(
          s"INIT_SESSION: no active sessions for ${is.groupId.value}. " +
            "Creating a new one..."
        )
        val s = Session(is.groupId, is.consumerLimit)
        producer.publish(s).map { _ =>
          logger.debug(
            s"INIT_SESSION: session ${is.groupId.value} initialised."
          )
          is.replyTo ! Session.SessionInitialised(s)
        }
        active.add(s).map(as => behavior(as)).getOrElse(Behaviors.same)
    }
  }

  private[this] def addConsumerHandler(
      active: ActiveSessions,
      ac: AddConsumer
  )(
      implicit ec: ExecutionContext,
      producer: SessionDataProducer
  ): Behavior[Protocol] = {
    logger.debug(
      s"ADD_CONSUMER: adding consumer ${ac.consumerId.value} connected to " +
        s"server ${ac.serverId.value} to session ${ac.groupId.value}..."
    )
    active.find(ac.groupId) match {
      case None =>
        logger.warn(
          s"ADD_CONSUMER: session ${ac.groupId.value} was not found. " +
            s"Consumer ID ${ac.consumerId} will not be added."
        )
        ac.replyTo ! Session.SessionNotFound(ac.groupId)
        Behaviors.same

      case Some(session) =>
        session.addConsumer(ac.consumerId, ac.serverId) match {
          case added @ Session.ConsumerAdded(s) =>
            producer.publish(s).map { _ =>
              logger.debug(
                s"ADD_CONSUMER: consumer ${ac.consumerId.value} added to " +
                  s"session ${ac.groupId.value} from instance " +
                  s"${ac.serverId.value}"
              )
              ac.replyTo ! added
            }
            Behaviors.same

          case notAdded =>
            logger.debug(
              s"ADD_CONSUMER: session op result for session " +
                s"${ac.groupId.value} trying to add consumer " +
                s"${ac.consumerId.value} on instance ${ac.serverId.value}: " +
                s"${notAdded.asString}"
            )
            ac.replyTo ! notAdded
            Behaviors.same
        }
    }
  }

  private[this] def removeConsumerHandler(
      active: ActiveSessions,
      rc: RemoveConsumer
  )(
      implicit ec: ExecutionContext,
      producer: SessionDataProducer
  ): Behavior[Protocol] = {
    logger.debug(
      s"REMOVE_CONSUMER: remove consumer ${rc.consumerId.value} from " +
        s"session ${rc.groupId.value} on server..."
    )
    active.find(rc.groupId) match {
      case None =>
        logger.debug(
          s"REMOVE_CONSUMER: session ${rc.groupId.value} was not found"
        )
        rc.replyTo ! Session.SessionNotFound(rc.groupId)
        Behaviors.same

      case Some(session) =>
        session.removeConsumer(rc.consumerId) match {
          case removed @ Session.ConsumerRemoved(s) =>
            producer.publish(s).foreach { _ =>
              logger.debug(
                s"REMOVE_CONSUMER: consumer ${rc.consumerId.value} removed " +
                  s"from session ${rc.groupId.value}"
              )
              rc.replyTo ! removed
            }
            Behaviors.same

          case notRemoved =>
            logger.debug(
              "REMOVE_CONSUMER: session op result " +
                s"for ${rc.groupId.value}: ${notRemoved.asString}"
            )
            rc.replyTo ! notRemoved
            Behaviors.same
        }
    }
  }

  private[this] def getSessionHandler(
      active: ActiveSessions,
      gs: GetSession
  ): Behavior[Protocol] = {
    gs.replyTo ! active.find(gs.groupId)
    Behaviors.same
  }

  private[this] def internalProtocolHandler(
      active: ActiveSessions,
      ip: InternalProtocol
  )(
      implicit ec: ExecutionContext,
      cfg: AppCfg,
      producer: SessionDataProducer
  ): Behavior[Protocol] = ip match {
    case UpdateSession(gid, s) =>
      logger.debug(s"INTERNAL: Updating session ${gid.value}...")
      nextOrSame(active.updateSession(gid, s))(behavior)

    case RemoveSession(gid) =>
      logger.debug(s"INTERNAL: Removing session ${gid.value}..")
      nextOrSame(active.removeSession(gid))(behavior)
  }

}
