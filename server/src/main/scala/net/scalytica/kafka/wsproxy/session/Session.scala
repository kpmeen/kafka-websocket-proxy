package net.scalytica.kafka.wsproxy.session

import net.scalytica.kafka.wsproxy.models.{WsClientId, WsGroupId, WsServerId}
import net.scalytica.kafka.wsproxy.session.Session._

case class Session(
    consumerGroupId: WsGroupId,
    consumers: Set[ConsumerInstance],
    consumerLimit: Int
) {

  def canOpenSocket: Boolean = consumers.size < consumerLimit

  def hasConsumer(consumerId: WsClientId): Boolean =
    consumers.exists(_.id == consumerId)

  def addConsumer(
      consumerId: WsClientId,
      serverId: WsServerId
  ): SessionOpResult =
    addConsumer(ConsumerInstance(consumerId, serverId))

  def addConsumer(consumerInstance: ConsumerInstance): SessionOpResult =
    if (hasConsumer(consumerInstance.id)) ConsumerExists(this)
    else {
      if (canOpenSocket) {
        ConsumerAdded(copy(consumers = consumers + consumerInstance))
      } else {
        ConsumerLimitReached(this)
      }
    }

  def removeConsumer(consumerId: WsClientId): SessionOpResult = {
    if (hasConsumer(consumerId)) {
      ConsumerRemoved(copy(consumers = consumers.filterNot(_.id == consumerId)))
    } else {
      ConsumerDoesNotExists(this)
    }
  }
}

case object Session {

  def apply(consumerGroupId: WsGroupId, consumerLimit: Int = 2): Session = {
    Session(
      consumerGroupId = consumerGroupId,
      consumers = Set.empty,
      consumerLimit = consumerLimit
    )
  }

  sealed trait SessionOpResult { self =>
    def session: Session

    def asString: String = {
      val tn = self.getClass.getTypeName
      tn.substring(tn.lastIndexOf("$"))
        .stripPrefix("$")
        .foldLeft("") { (str, in) =>
          if (in.isUpper) str + " " + in.toLower
          else str + in
        }
        .trim
    }
  }

  case class SessionInitialised(session: Session)    extends SessionOpResult
  case class ConsumerAdded(session: Session)         extends SessionOpResult
  case class ConsumerRemoved(session: Session)       extends SessionOpResult
  case class ConsumerExists(session: Session)        extends SessionOpResult
  case class ConsumerLimitReached(session: Session)  extends SessionOpResult
  case class ConsumerDoesNotExists(session: Session) extends SessionOpResult

  case object ConsumersForServerRemoved extends SessionOpResult {

    override def session =
      throw new NoSuchElementException(
        "Cannot access session value when it's not found"
      )
  }

  case class SessionNotFound(groupId: WsGroupId) extends SessionOpResult {

    override def session =
      throw new NoSuchElementException(
        "Cannot access session value when it's not found"
      )
  }

  case class IncompleteOperation(reason: String) extends SessionOpResult {

    override def session =
      throw new NoSuchElementException(
        "Cannot access session value when it's not found"
      )
  }

}

/**
 * Wraps information about each instantiated consumer within a [[Session]].
 *
 * @param id
 *   The client (or consumer) ID given.
 * @param serverId
 *   The server ID where the consumer instance is running.
 */
case class ConsumerInstance(id: WsClientId, serverId: WsServerId)
