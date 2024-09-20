package net.scalytica.kafka.wsproxy.models

import java.util.Locale

import org.apache.kafka.common.IsolationLevel

sealed trait ReadIsolationLevel {
  protected val level: IsolationLevel
  lazy val value: String               = level.toString.toLowerCase(Locale.ROOT)
  def isSameName(str: String): Boolean = value.equalsIgnoreCase(str)
}

object ReadIsolationLevel {

  def fromString(str: String): Option[ReadIsolationLevel] = {
    Option(str).flatMap {
      case s if ReadUncommitted.isSameName(s) => Some(ReadUncommitted)
      case s if ReadCommitted.isSameName(s)   => Some(ReadCommitted)
      case _                                  => None
    }
  }

  def unsafeFromString(str: String): ReadIsolationLevel =
    fromString(str).getOrElse {
      throw new IllegalArgumentException(
        s"Read isolation '$str' is not a valid value. Please use one of" +
          s" '${ReadCommitted.value}' or '${ReadUncommitted.value}'."
      )
    }

}

case object ReadUncommitted extends ReadIsolationLevel {
  override protected val level = IsolationLevel.READ_UNCOMMITTED
}

case object ReadCommitted extends ReadIsolationLevel {
  override protected val level = IsolationLevel.READ_COMMITTED
}
