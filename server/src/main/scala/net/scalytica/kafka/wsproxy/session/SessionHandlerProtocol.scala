package net.scalytica.kafka.wsproxy.session

import akka.actor.typed.ActorRef
import net.scalytica.kafka.wsproxy.models._

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

  sealed trait AddClientCmd[ID <: FullClientId] {
    val sessionId: SessionId
    val serverId: WsServerId
    val fullClientId: ID
    val replyTo: ActorRef[SessionOpResult]

    lazy val clientIdString: String = fullClientId.value
  }

  sealed trait RemoveClientCmd[ID <: FullClientId] {
    val sessionId: SessionId
    val fullClientId: ID
    val replyTo: ActorRef[SessionOpResult]

    lazy val clientIdString: String = fullClientId.value
  }

  /**
   * Command message to terminate the session handler
   *
   * @param replyTo
   *   the [[ActorRef]] that sent the command
   */
  final case class StopSessionHandler(
      replyTo: ActorRef[SessionOpResult]
  ) extends SessionProtocol

  final case class SessionHandlerReady(
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
      serverId: WsServerId,
      fullClientId: FullConsumerId,
      replyTo: ActorRef[SessionOpResult]
  ) extends ConsumerSessionProtocol
      with AddClientCmd[FullConsumerId]

  final case class RemoveConsumer(
      sessionId: SessionId,
      fullClientId: FullConsumerId,
      replyTo: ActorRef[SessionOpResult]
  ) extends ConsumerSessionProtocol
      with RemoveClientCmd[FullConsumerId]

  final case class GetConsumerSession(
      sessionId: SessionId,
      replyTo: ActorRef[Option[ConsumerSession]]
  ) extends ConsumerSessionProtocol

  // Producer session specific commands

  final case class InitProducerSession(
      sessionId: SessionId,
      producerId: WsProducerId,
      maxConnections: Int,
      replyTo: ActorRef[SessionOpResult]
  ) extends ProducerSessionProtocol
      with SessionInitCmd

  final case class AddProducer(
      sessionId: SessionId,
      serverId: WsServerId,
      fullClientId: FullProducerId,
      replyTo: ActorRef[SessionOpResult]
  ) extends ProducerSessionProtocol
      with AddClientCmd[FullProducerId]

  final case class RemoveProducer(
      sessionId: SessionId,
      fullClientId: FullProducerId,
      replyTo: ActorRef[SessionOpResult]
  ) extends ProducerSessionProtocol
      with RemoveClientCmd[FullProducerId]

  final case class GetProducerSession(
      sessionId: SessionId,
      replyTo: ActorRef[Option[ProducerSession]]
  ) extends ProducerSessionProtocol

  /** Sub-protocol only to be used by the Kafka consumer */
  sealed private[session] trait InternalSessionProtocol extends Protocol {
    val offset: Long
  }

  final private[session] case class UpdateSession(
      sessionId: SessionId,
      s: Session,
      offset: Long
  ) extends InternalSessionProtocol

  final private[session] case class RemoveSession(
      sessionId: SessionId,
      offset: Long
  ) extends InternalSessionProtocol
}
