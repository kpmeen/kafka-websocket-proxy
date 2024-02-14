package net.scalytica.kafka.wsproxy.web

import net.scalytica.kafka.wsproxy.StringExtensions

object SocketProtocol {

  sealed trait SocketPayload { self =>

    lazy val name: String =
      self.niceClassSimpleName.stripSuffix("Payload").toSnakeCase

    def isSameName(s: String): Boolean = name.equalsIgnoreCase(s)
  }

  object SocketPayload {

    def fromString(string: String): Option[SocketPayload] =
      Option(string).flatMap {
        case s: String if JsonPayload.isSameName(s) => Some(JsonPayload)
        case _                                      => None
      }

    def unsafeFromString(s: String): SocketPayload =
      fromString(s).getOrElse {
        throw new IllegalArgumentException(
          s"'$s' is not a valid socket payload"
        )
      }
  }

  case object JsonPayload extends SocketPayload

}
