package net.scalytica.kafka.wsproxy.utils

import net.scalytica.kafka.wsproxy.Configuration.KafkaBootstrapUrls
import net.scalytica.test.WSProxyKafkaSpec
import org.scalatest.{MustMatchers, WordSpec}

import scala.concurrent.duration._

class HostResolverSpec
    extends WordSpec
    with MustMatchers
    with WSProxyKafkaSpec {

  "The HostResolver" should {

    "fail resolution after retrying an action for a given duration" in {
      implicit val cfg = defaultTestAppCfg.copy(
        kafkaClient = defaultTestAppCfg.kafkaClient.copy(
          brokerResolutionTimeout = 5 seconds
        )
      )
      val hosts = KafkaBootstrapUrls(List("foo"))

      HostResolver.resolveKafkaBootstrapHosts(hosts).isLeft mustBe true
    }

    "successfully resolve a host" in {
      implicit val cfg = defaultTestAppCfg.copy(
        kafkaClient = defaultTestAppCfg.kafkaClient.copy(
          brokerResolutionTimeout = 5 seconds
        )
      )
      val hosts = KafkaBootstrapUrls(List("github.com"))

      HostResolver.resolveKafkaBootstrapHosts(hosts).isRight mustBe true
    }

  }

}
