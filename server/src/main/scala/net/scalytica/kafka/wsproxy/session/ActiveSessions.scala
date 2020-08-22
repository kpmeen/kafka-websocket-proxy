package net.scalytica.kafka.wsproxy.session

import net.scalytica.kafka.wsproxy.models.WsGroupId

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
) {

  def find(groupId: WsGroupId): Option[Session] = sessions.get(groupId)

  def add(session: Session): Either[String, ActiveSessions] = {
    sessions.find(_._1 == session.consumerGroupId) match {
      case Some(_) =>
        Left(s"Session for ${session.consumerGroupId} already exists")

      case None =>
        val ns = session.consumerGroupId -> session
        Right(copy(sessions = sessions + ns))
    }
  }

  def updateSession(
      groupId: WsGroupId,
      session: Session
  ): Either[String, ActiveSessions] = {
    sessions.find(_._1 == groupId) match {
      case None =>
        // Initialise session if it doesn't exist.
        add(session)

      case Some((gid, _)) =>
        Right(copy(sessions = sessions.updated(gid, session)))
    }
  }

  def removeSession(groupId: WsGroupId): Either[String, ActiveSessions] = {
    sessions.find(_._1 == groupId) match {
      case None    => Left(s"Session for $groupId does not exist")
      case Some(_) => Right(copy(sessions = sessions - groupId))
    }
  }
}

object ActiveSessions {

  def apply(session: Session*): ActiveSessions =
    ActiveSessions(session.map(s => s.consumerGroupId -> s).toMap)

}
