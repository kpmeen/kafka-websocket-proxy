package net.scalytica.kafka.wsproxy

import akka.http.scaladsl.model.headers.{
  BasicHttpCredentials,
  ModeledCustomHeader,
  ModeledCustomHeaderCompanion
}

import scala.util.Try

object Headers {

  val KafkaAuthHeaderName = "X-Kafka-Auth"

  object XKafkaAuthHeader
      extends ModeledCustomHeaderCompanion[XKafkaAuthHeader] {
    override def name: String = KafkaAuthHeaderName

    override def parse(value: String): Try[XKafkaAuthHeader] =
      Try {
        val creds = BasicHttpCredentials(value)
        XKafkaAuthHeader(creds)
      }
  }

  final case class XKafkaAuthHeader(credentials: BasicHttpCredentials)
      extends ModeledCustomHeader[XKafkaAuthHeader] {

    override def companion                    = XKafkaAuthHeader
    override def renderInRequests(): Boolean  = true
    override def renderInResponses(): Boolean = false

    override def value(): String = credentials.token()
  }

}

object SocketProtocol {

  sealed trait SocketPayload { self =>

    lazy val name: String = self.getClass.getSimpleName
      .stripSuffix("$")
      .stripSuffix("Payload")
      .toSnakeCase

    def isSameName(s: String): Boolean = name.equalsIgnoreCase(s)
  }

  object SocketPayload {

    def fromString(string: String): Option[SocketPayload] =
      Option(string).flatMap {
        case s: String if JsonPayload.isSameName(s) => Some(JsonPayload)
        case s: String if AvroPayload.isSameName(s) => Some(AvroPayload)
        case _                                      => None
      }

    def unsafeFromString(s: String): SocketPayload =
      fromString(s).getOrElse {
        throw new IllegalArgumentException(s"$s is not a valid socket payload")
      }
  }

  case object JsonPayload extends SocketPayload
  case object AvroPayload extends SocketPayload

}
