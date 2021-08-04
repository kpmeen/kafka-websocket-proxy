package net.scalytica.kafka.wsproxy.session

import net.scalytica.kafka.wsproxy.logging.WithProxyLogger
import net.scalytica.kafka.wsproxy.models.{WsGroupId, WsServerId}

/**
 * Data type for keeping track of active sessions. Each active session is
 * represented as a key-value pair, where the key is the consumer group id.
 *
 * @param sessions
 *   a [[Map]] with [[WsGroupId]] keys and [[Session]] values.
 * @see
 *   [[Session]]
 */
case class ActiveSessions(
    sessions: Map[WsGroupId, Session] = Map.empty
) extends WithProxyLogger {

  def find(groupId: WsGroupId): Option[Session] = sessions.get(groupId)

  def removeConsumersFromServerId(
      serverId: WsServerId
  ): ActiveSessions = {
    logger.trace(s"Removing all sessions for $serverId...")
    val s = sessions.map { case (gid, session) =>
      gid -> session.copy(consumers =
        session.consumers.filterNot(_.serverId == serverId)
      )
    }
    copy(sessions = s)
  }

  def add(session: Session): Either[String, ActiveSessions] = {
    sessions.find(_._1 == session.consumerGroupId) match {
      case Some(_) =>
        val msg = s"Session for ${session.consumerGroupId} already exists"
        logger.trace(msg)
        Left(msg)

      case None =>
        logger.trace(s"Adding session $session...")
        val ns = session.consumerGroupId -> session
        Right(copy(sessions = sessions + ns))
    }
  }

  def updateSession(
      groupId: WsGroupId,
      session: Session
  ): Either[String, ActiveSessions] = {
    logger.trace(s"Updating session for $groupId...")
    sessions.find(_._1 == groupId) match {
      case None =>
        logger.trace(s"Session for $groupId was not found...")
        add(session)

      case Some((gid, _)) =>
        Right(copy(sessions = sessions.updated(gid, session)))
    }
  }

  def removeSession(groupId: WsGroupId): Either[String, ActiveSessions] = {
    sessions.find(_._1 == groupId) match {
      case None =>
        val msg = s"Session for $groupId does not exist"
        logger.trace(msg)
        Left(msg)
      case Some(_) =>
        logger.trace(s"Removing session for $groupId")
        Right(copy(sessions = sessions - groupId))
    }
  }
}

object ActiveSessions {

  def apply(session: Session*): ActiveSessions =
    ActiveSessions(session.map(s => s.consumerGroupId -> s).toMap)

}
