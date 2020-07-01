package net.scalytica.kafka.wsproxy.session

import akka.actor.typed.Scheduler
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.{ActorRef, Behavior}
import akka.kafka.scaladsl.Consumer
import akka.stream.scaladsl.{RunnableGraph, Sink}
import akka.util.Timeout
import net.scalytica.kafka.wsproxy.Configuration.AppCfg
import net.scalytica.kafka.wsproxy.admin.WsKafkaAdminClient
import net.scalytica.kafka.wsproxy.logging.WithProxyLogger
import net.scalytica.kafka.wsproxy.models.{WsClientId, WsGroupId, WsServerId}
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

  case object Stop extends ClientSessionProtocol

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

    def initSession(groupId: WsGroupId, consumerLimit: Int)(
        implicit timeout: Timeout,
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

    def addConsumer(
        groupId: WsGroupId,
        clientId: WsClientId,
        serverId: WsServerId
    )(
        implicit timeout: Timeout,
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

    def removeConsumer(groupId: WsGroupId, clientId: WsClientId)(
        implicit timeout: Timeout,
        scheduler: Scheduler
    ): Future[SessionOpResult] = {
      sh.ask[SessionOpResult] { ref =>
        SessionHandlerProtocol.RemoveConsumer(groupId, clientId, ref)
      }
    }

    def getSession(groupId: WsGroupId)(
        implicit timeout: Timeout,
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
trait SessionHandler extends WithProxyLogger {

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
   * @return a [[SessionHandler.SessionHandlerRef]] containing a reference to
   *         the [[RunnableGraph]] that executes the [[SessionDataConsumer]]
   *         stream. And a typed [[ActorRef]] that understands messages from the
   *         defined protocol in [[SessionHandlerProtocol.Protocol]].
   */
  def init(
      implicit cfg: AppCfg,
      sys: akka.actor.ActorSystem
  ): SessionHandler.SessionHandlerRef = {
    implicit val typedSys = sys.toTyped

    val handlerName = s"session-handler-actor-${cfg.server.serverId.value}"
    logger.debug(s"Initialising session handler $handlerName...")

    val admin = new WsKafkaAdminClient(cfg)
    try {
      val ready = admin.clusterReady
      if (ready) admin.initSessionStateTopic()
      else {
        logger.error("Could not reach Kafka cluster. Terminating service!")
        System.exit(1)
      }
    } finally {
      admin.close()
    }

    val ref =
      sys.spawn(sessionHandler, handlerName)

    logger.debug(
      s"Starting session state consumer for server id $handlerName..."
    )

    val consumer = new SessionDataConsumer()

    val consumerStream: RunnableGraph[Consumer.Control] =
      consumer.sessionStateSource.to(Sink.foreach {
        case ip: InternalProtocol =>
          logger.debug(s"Consumed message: $ip")
          ref ! ip

        case _ => ()
      })

    SessionHandler.SessionHandlerRef(consumerStream, ref)
  }

  // scalastyle:off method.length cyclomatic.complexity
  protected def sessionHandler(implicit cfg: AppCfg): Behavior[Protocol] = {
    val serverId = cfg.server.serverId

    Behaviors.setup { implicit ctx =>
      implicit val sys = ctx.system
      implicit val ec  = sys.executionContext

      logger.debug("Initialising session data producer...")
      implicit val producer = new SessionDataProducer()

      def behavior(
          active: ActiveSessions = ActiveSessions()
      ): Behavior[Protocol] =
        Behaviors.receiveMessage {
          case csp: ClientSessionProtocol =>
            csp match {
              case InitSession(gid, limit, replyTo) =>
                logger.debug(
                  s"INIT_SESSION: initialise session ${gid.value} " +
                    s"with limit $limit"
                )
                active.find(gid) match {
                  case Some(s) =>
                    logger.debug(
                      s"INIT_SESSION: session ${gid.value} already initialised."
                    )
                    replyTo ! Session.SessionInitialised(s)
                    Behaviors.same

                  case None =>
                    val s = Session(gid, limit)
                    producer.publish(s).map { _ =>
                      logger.debug(
                        s"INIT_SESSION: session ${gid.value} initialised."
                      )
                      replyTo ! Session.SessionInitialised(s)
                    }
                    active
                      .add(s)
                      .map(as => behavior(as))
                      .getOrElse(Behaviors.same)
                }

              case AddConsumer(gid, cid, sid, replyTo) =>
                logger.debug(
                  s"ADD_CONSUMER: add consumer ${cid.value} connected to " +
                    s"server ${sid.value} to session ${gid.value}..."
                )
                active.find(gid) match {
                  case None =>
                    logger.debug(
                      s"ADD_CONSUMER: session ${gid.value} was not found."
                    )
                    replyTo ! Session.SessionNotFound(gid)
                    Behaviors.same

                  case Some(session) =>
                    session.addConsumer(cid, sid) match {
                      case added @ Session.ConsumerAdded(s) =>
                        producer.publish(s).map { _ =>
                          logger.debug(
                            s"ADD_CONSUMER: consumer ${cid.value} added to" +
                              s" session ${gid.value}"
                          )
                          replyTo ! added
                        }
                        Behaviors.same

                      case notAdded =>
                        logger.debug(
                          s"ADD_CONSUMER: session op result for ${gid.value}:" +
                            s" ${notAdded.asString}"
                        )
                        replyTo ! notAdded
                        Behaviors.same
                    }
                }

              case RemoveConsumer(gid, cid, replyTo) =>
                logger.debug(
                  s"REMOVE_CONSUMER: remove consumer ${cid.value} from " +
                    s"session ${gid.value} on server..."
                )
                active.find(gid) match {
                  case None =>
                    logger.debug(
                      s"REMOVE_CONSUMER: session ${gid.value} was not found"
                    )
                    replyTo ! Session.SessionNotFound(gid)
                    Behaviors.same

                  case Some(session) =>
                    session.removeConsumer(cid) match {
                      case removed @ Session.ConsumerRemoved(s) =>
                        producer.publish(s).map { _ =>
                          logger.debug(
                            s"REMOVE_CONSUMER: consumer ${cid.value} removed " +
                              s"from session ${gid.value}"
                          )
                          replyTo ! removed
                        }
                        Behaviors.same

                      case notRemoved =>
                        logger.debug(
                          "REMOVE_CONSUMER: session op result " +
                            s"for ${gid.value}: ${notRemoved.asString}"
                        )
                        replyTo ! notRemoved
                        Behaviors.same
                    }
                }

              case GetSession(gid, replyTo) =>
                replyTo ! active.find(gid)
                Behaviors.same

              case Stop =>
                logger.debug(
                  s"STOP: stopping session handler for server ${serverId.value}"
                )
                Behaviors.stopped
            }

          case ip: InternalProtocol =>
            ip match {
              case UpdateSession(gid, s) =>
                logger.debug(s"INTERNAL: Updating session ${gid.value}...")
                nextOrSame(active.updateSession(gid, s))(behavior)

              case RemoveSession(gid) =>
                logger.debug(s"INTERNAL: Removing session ${gid.value}..")
                nextOrSame(active.removeSession(gid))(behavior)
            }
        }

      behavior()
    }
  }

  // scalastyle:on
}
