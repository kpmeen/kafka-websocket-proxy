package net.scalytica.kafka.wsproxy.web

import org.apache.pekko.http.scaladsl.model.headers.BasicHttpCredentials
import net.scalytica.kafka.wsproxy.web.Headers.XKafkaAuthHeader
import org.scalatest.OptionValues
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec

class HeadersSpec extends AnyWordSpec with Matchers with OptionValues {

  "The X-Kafka-Auth header" should {

    "correctly parse a header String" in {
      val usr = "foo"
      val pwd = "bar"

      val basicCreds = BasicHttpCredentials(usr, pwd)
      val header     = XKafkaAuthHeader(basicCreds)

      val parsed = XKafkaAuthHeader.parse(header.value()).toOption

      header mustBe parsed.value
      parsed.value.credentials.username mustBe usr
      parsed.value.credentials.password mustBe pwd
    }

  }

}
