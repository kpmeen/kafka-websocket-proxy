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
