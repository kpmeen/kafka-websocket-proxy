package net.scalytica.kafka.wsproxy.session

/** Result type used for operations to alter the session state. */
sealed trait SessionOpResult { self =>
  def session: Session

  def asString: String = {
    val tn = self.getClass.getTypeName
    val clean =
      if (tn.contains('$')) tn.substring(tn.lastIndexOf("$")).stripPrefix("$")
      else tn

    clean
      .foldLeft("") { (str, in) =>
        if (in.isUpper) str + " " + in.toLower
        else str + in
      }
      .trim
  }
}

case class SessionStateRestored() extends SessionOpResult {
  override def session =
    throw new NoSuchElementException(
      "No access to session instance when restoring global session state"
    )
}

case class RestoringSessionState() extends SessionOpResult {
  override def session =
    throw new NoSuchElementException(
      "No access to session instance when restoring global session state"
    )
}

case object ProducerSessionsDisabled extends SessionOpResult {
  override def session =
    throw new NoSuchElementException(
      "Producer sessions have been disabled in the configuration."
    )
}

case class SessionInitialised(session: Session) extends SessionOpResult

case class InstanceAdded(session: Session) extends SessionOpResult

case class InstanceRemoved(session: Session) extends SessionOpResult

case class InstanceExists(session: Session) extends SessionOpResult

case class InstanceTypeForSessionIncorrect(session: Session)
    extends SessionOpResult

case class InstanceLimitReached(session: Session) extends SessionOpResult

case class InstanceDoesNotExists(session: Session) extends SessionOpResult

case class ProducerInstanceMissingId(session: Session) extends SessionOpResult

case class InstancesForServerRemoved() extends SessionOpResult {

  override def session =
    throw new NoSuchElementException(
      "Cannot access session value when it's not found"
    )
}

case class SessionNotFound(sessionId: SessionId) extends SessionOpResult {

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
