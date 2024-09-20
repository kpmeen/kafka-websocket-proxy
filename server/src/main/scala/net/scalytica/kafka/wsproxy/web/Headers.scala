package net.scalytica.kafka.wsproxy.web

import scala.util.Try

import net.scalytica.kafka.wsproxy.models.AclCredentials

import org.apache.pekko.http.scaladsl.model.headers.BasicHttpCredentials
import org.apache.pekko.http.scaladsl.model.headers.ModeledCustomHeader
import org.apache.pekko.http.scaladsl.model.headers.ModeledCustomHeaderCompanion

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

    override def companion: ModeledCustomHeaderCompanion[XKafkaAuthHeader] =
      XKafkaAuthHeader

    override def renderInRequests(): Boolean = true

    override def renderInResponses(): Boolean = false

    override def value(): String = credentials.token()

    def aclCredentials: AclCredentials =
      AclCredentials(credentials.username, credentials.password)
  }

}
