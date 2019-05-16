package net.scalytica.kafka.wsproxy.session

import akka.actor.Scheduler
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.{ActorRef, Behavior}
import akka.kafka.scaladsl.Consumer
import akka.stream.scaladsl.{RunnableGraph, Sink}
import akka.util.Timeout
import com.typesafe.scalalogging.Logger
import net.scalytica.kafka.wsproxy.Configuration.AppCfg
import net.scalytica.kafka.wsproxy.admin.WsKafkaAdminClient
import net.scalytica.kafka.wsproxy.session.Session.SessionOpResult
import net.scalytica.kafka.wsproxy.session.SessionHandlerProtocol._

import scala.concurrent.Future

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
      groupId: String,
      consumerLimit: Int,
      replyTo: ActorRef[Session.SessionOpResult]
  ) extends ClientSessionProtocol

  case class AddConsumer(
      groupId: String,
      consumerId: String,
      serverId: String,
      replyTo: ActorRef[Session.SessionOpResult]
  ) extends ClientSessionProtocol

  case class RemoveConsumer(
      groupId: String,
      consumerId: String,
      replyTo: ActorRef[Session.SessionOpResult]
  ) extends ClientSessionProtocol

  case class GetSession(
      groupId: String,
      replyTo: ActorRef[Option[Session]]
  ) extends ClientSessionProtocol

  case object Stop extends ClientSessionProtocol

  /** Sub-protocol only to be used by the Kafka consumer */
  sealed private[session] trait InternalProtocol extends Protocol

  private[session] case class UpdateSession(
      groupId: String,
      s: Session
  ) extends InternalProtocol

  private[session] case class RemoveSession(
      groupId: String
  ) extends InternalProtocol
}

object SessionHandler extends SessionHandler {

  implicit class SessionHandlerOpExtensions(
      sh: ActorRef[SessionHandlerProtocol.Protocol]
  ) {

    def initSession(groupId: String, consumerLimit: Int)(
        implicit
        timeout: Timeout,
        scheduler: Scheduler
    ): Future[SessionOpResult] = {
      sh.ask[SessionOpResult] { ref =>
        SessionHandlerProtocol.InitSession(
          groupId = groupId,
          consumerLimit = consumerLimit,
          replyTo = ref
        )
      }
    }

    def addConsumer(groupId: String, clientId: String, serverId: String)(
        implicit
        timeout: Timeout,
        scheduler: Scheduler
    ): Future[SessionOpResult] = {
      sh.ask[SessionOpResult] { ref =>
        SessionHandlerProtocol.AddConsumer(
          groupId = groupId,
          consumerId = clientId,
          serverId = serverId,
          replyTo = ref
        )
      }
    }

    def removeConsumer(groupId: String, clientId: String)(
        implicit
        timeout: Timeout,
        scheduler: Scheduler
    ): Future[SessionOpResult] = {
      sh.ask[SessionOpResult] { ref =>
        SessionHandlerProtocol.RemoveConsumer(groupId, clientId, ref)
      }
    }

    def getSession(groupId: String)(
        implicit
        timeout: Timeout,
        scheduler: Scheduler
    ): Future[Option[Session]] = {
      sh.ask[Option[Session]] { ref =>
        SessionHandlerProtocol.GetSession(groupId, ref)
      }
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
 * - Keeping track of number of sockets per consumer group
 * - Keeping track of which consumers are part of the consumer group
 * - Possibility to share meta-data across nodes
 * - etc...
 */
trait SessionHandler {
  private[this] val logger = Logger(getClass)

  /**
   * Calculates the expected next behaviour based on the value of the {{either}}
   * argument.
   *
   * @param either the value to calculate the next behaviour from.
   * @param behavior the behaviour to use in the case of a rhs value.
   * @return the next behaviour to use.
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
   * @param cfg implicit [[AppCfg]] to use
   * @param sys the untyped [[akka.actor.ActorSystem]] to use
   * @return a tuple containing a reference to the [[RunnableGraph]] that
   *         executes the [[SessionDataConsumer]] stream. And a typed
   *         [[ActorRef]] that understands messages from the defined protocol in
   *         [[SessionHandlerProtocol.Protocol]].
   */
  def init(
      implicit
      cfg: AppCfg,
      sys: akka.actor.ActorSystem
  ): (RunnableGraph[Consumer.Control], ActorRef[Protocol]) = {
    implicit val typedSys = sys.toTyped

    val sessionHandlerName = s"session-handler-actor-${cfg.server.serverId}"
    logger.debug(s"Initialising session handler $sessionHandlerName...")

    val ref =
      sys.spawn(sessionHandler, sessionHandlerName)

    logger.debug(
      s"Starting session state consumer for server id $sessionHandlerName..."
    )

    val consumer = new SessionDataConsumer()

    val consumerStream: RunnableGraph[Consumer.Control] =
      consumer.sessionStateSource.to(Sink.foreach {
        case ip: InternalProtocol =>
          logger.debug(s"Consumed message: $ip")
          ref ! ip

        case _ => ()
      })

    (consumerStream, ref)
  }

  // scalastyle:off method.length cyclomatic.complexity
  protected def sessionHandler(implicit cfg: AppCfg): Behavior[Protocol] = {
    val adminClient = new WsKafkaAdminClient(cfg)
    val serverId    = cfg.server.serverId

    Behaviors.setup { implicit ctx =>
      implicit val sys = ctx.system
      implicit val ec  = sys.executionContext

      val log = ctx.log.withLoggerClass(classOf[SessionHandler])

      adminClient.initSessionStateTopic().foreach { _ =>
        ctx.log.info("Sessions state topic initialised")
      }

      log.debug("Initialising session data producer...")
      implicit val producer = new SessionDataProducer()

      def behavior(
          active: ActiveSessions = ActiveSessions()
      ): Behavior[Protocol] = Behaviors.receiveMessage {
        case csp: ClientSessionProtocol =>
          csp match {
            case InitSession(gid, limit, replyTo) =>
              log.debug(
                s"INIT_SESSION: initialise session $gid with limit $limit"
              )
              active.find(gid) match {
                case Some(s) =>
                  log.debug(s"INIT_SESSION: session $gid already initialised.")
                  replyTo ! Session.SessionInitialised(s)
                  Behaviors.same

                case None =>
                  val s = Session(gid, limit)
                  producer.publish(s).map { _ =>
                    log.debug(s"INIT_SESSION: session $gid initialised.")
                    replyTo ! Session.SessionInitialised(s)
                  }
                  active
                    .add(s)
                    .map(as => behavior(as))
                    .getOrElse(Behaviors.same)
              }

            case AddConsumer(gid, cid, sid, replyTo) =>
              log.debug(s"ADD_CONSUMER: add consumer $cid to session $gid...")
              active.find(gid) match {
                case None =>
                  log.debug(s"ADD_CONSUMER: session $gid was not found.")
                  replyTo ! Session.SessionNotFound(gid)
                  Behaviors.same

                case Some(session) =>
                  session.addConsumer(cid, sid) match {
                    case added @ Session.ConsumerAdded(s) =>
                      producer.publish(s).map { _ =>
                        log.debug(
                          s"ADD_CONSUMER: consumer added to session $gid"
                        )
                        replyTo ! added
                      }
                      Behaviors.same

                    case notAdded =>
                      log.debug(
                        s"ADD_CONSUMER: session op result for $gid:" +
                          s" ${notAdded.asString}"
                      )
                      replyTo ! notAdded
                      Behaviors.same
                  }
              }

            case RemoveConsumer(gid, cid, replyTo) =>
              log.debug(
                s"REMOVE_CONSUMER: remove consumer $cid to session $gid..."
              )
              active.find(gid) match {
                case None =>
                  log.debug(s"REMOVE_CONSUMER: session $gid was not found")
                  replyTo ! Session.SessionNotFound(gid)
                  Behaviors.same

                case Some(session) =>
                  session.removeConsumer(cid) match {
                    case removed @ Session.ConsumerRemoved(s) =>
                      producer.publish(s).map { _ =>
                        log.debug(
                          s"REMOVE_CONSUMER: consumer removed from session $gid"
                        )
                        replyTo ! removed
                      }
                      Behaviors.same

                    case notRemoved =>
                      log.debug(
                        "REMOVE_CONSUMER: session op result " +
                          s"for $gid: ${notRemoved.asString}"
                      )
                      replyTo ! notRemoved
                      Behaviors.same
                  }
              }

            case GetSession(gid, replyTo) =>
              replyTo ! active.find(gid)
              Behaviors.same

            case Stop =>
              log.debug(s"STOP: stopping session handler for server $serverId")
              Behaviors.stopped
          }

        case ip: InternalProtocol =>
          ip match {
            case UpdateSession(gid, s) =>
              log.debug(s"INTERNAL: Updating session $gid...")
              nextOrSame(active.updateSession(gid, s))(behavior)

            case RemoveSession(gid) =>
              log.debug(s"INTERNAL: Removing session $gid..")
              nextOrSame(active.removeSession(gid))(behavior)
          }
      }

      behavior()
    }
  }

  // scalastyle:on
}
