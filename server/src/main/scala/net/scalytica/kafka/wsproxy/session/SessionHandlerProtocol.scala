package net.scalytica.kafka.wsproxy.session

import net.scalytica.kafka.wsproxy.config.Configuration.ConsumerSpecificLimitCfg
import net.scalytica.kafka.wsproxy.config.Configuration.ProducerSpecificLimitCfg
import net.scalytica.kafka.wsproxy.models._

import org.apache.pekko.actor.typed.ActorRef

/**
 * Encapsulates the communication protocol to use with the [[SessionHandler]]
 * Actor. It's split into two distinct sub-protocols. Where one is intended to
 * be used by the main application flow, and the other is to manipulate and
 * update the active sessions state.
 */
object SessionHandlerProtocol {

  /** Main protocol to be used by the session handler */
  sealed trait SessionProtocol

  /**
   * Protocol to use for session related behaviour from outside the session
   * handler.
   */
  sealed trait ClientSessionProtocol extends SessionProtocol
  /** Consumer session specific protocol */
  sealed trait ConsumerSessionProtocol extends ClientSessionProtocol
  /** Producer session specific protocol */
  sealed trait ProducerSessionProtocol extends ClientSessionProtocol

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
  }

  sealed trait RemoveClientCmd[ID <: FullClientId] {
    val sessionId: SessionId
    val fullClientId: ID
    val replyTo: ActorRef[SessionOpResult]
  }

  /**
   * Command message to terminate the session handler
   *
   * @param replyTo
   *   the [[ActorRef]] that sent the command
   */
  final case class StopSessionHandler(
      replyTo: ActorRef[SessionOpResult]
  ) extends ClientSessionProtocol

  final case class CheckSessionHandlerReady(
      replyTo: ActorRef[SessionOpResult]
  ) extends ClientSessionProtocol

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

  final case class UpdateConsumerSession(
      sessionId: SessionId,
      cfg: ConsumerSpecificLimitCfg,
      replyTo: ActorRef[SessionOpResult]
  ) extends ConsumerSessionProtocol

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

  final case class UpdateProducerSession(
      sessionId: SessionId,
      cfg: ProducerSpecificLimitCfg,
      replyTo: ActorRef[SessionOpResult]
  ) extends ProducerSessionProtocol

  final case class GetProducerSession(
      sessionId: SessionId,
      replyTo: ActorRef[Option[ProducerSession]]
  ) extends ProducerSessionProtocol

  /** Internal Sub-protocol only to be used by the internal Kafka consumer */
  sealed private[session] trait InternalSessionProtocol
      extends SessionProtocol {
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
