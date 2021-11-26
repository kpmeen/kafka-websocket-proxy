package net.scalytica.kafka.wsproxy.session

import net.scalytica.kafka.wsproxy.logging.WithProxyLogger
import net.scalytica.kafka.wsproxy.models.WsServerId

/**
 * Data type for keeping track of active sessions. Each active session is
 * represented as a key-value pair, where the key is the consumer group id.
 *
 * @param sessions
 *   a [[Map]] with [[SessionId]] keys and [[Session]] values.
 * @see
 *   [[Session]]
 */
case class ActiveSessions(
    sessions: Map[SessionId, Session] = Map.empty
) extends WithProxyLogger {

  def find(sessionId: SessionId): Option[Session] = sessions.get(sessionId)

  def removeInstancesFromServerId(serverId: WsServerId): ActiveSessions = {
    logger.trace(s"Removing all sessions for $serverId...")
    val s = sessions.map { case (sid, session) =>
      sid -> session.removeInstancesFromServerId(serverId)
    }
    copy(sessions = s)
  }

  def add(session: Session): Either[String, ActiveSessions] = {
    sessions.find(_._1 == session.sessionId) match {
      case Some(_) =>
        val msg = s"Session for ${session.sessionId} already exists"
        logger.trace(msg)
        Left(msg)

      case None =>
        logger.trace(s"Adding session $session...")
        val ns = session.sessionId -> session
        Right(copy(sessions = sessions + ns))
    }
  }

  def updateSession(
      sessionId: SessionId,
      session: Session
  ): Either[String, ActiveSessions] = {
    logger.trace(s"Updating session for $sessionId...")
    sessions.find(_._1 == sessionId) match {
      case None =>
        logger.trace(s"Session for $sessionId was not found...")
        add(session)

      case Some((gid, _)) =>
        Right(copy(sessions = sessions.updated(gid, session)))
    }
  }

  def removeSession(sessionId: SessionId): Either[String, ActiveSessions] = {
    sessions.find(_._1 == sessionId) match {
      case None =>
        val msg = s"Session for $sessionId does not exist"
        logger.trace(msg)
        Left(msg)
      case Some(_) =>
        logger.trace(s"Removing session for $sessionId")
        Right(copy(sessions = sessions - sessionId))
    }
  }
}

object ActiveSessions {

  def apply(session: Session*): ActiveSessions =
    ActiveSessions(session.map(s => s.sessionId -> s).toMap)

}
