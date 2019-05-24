package net.scalytica.kafka.wsproxy.utils

import net.scalytica.kafka.wsproxy.Configuration.KafkaBootstrapHosts
import net.scalytica.kafka.wsproxy.utils.HostResolver.resolveKafkaBootstrapHosts
import net.scalytica.test.WSProxyKafkaSpec
import org.scalatest.{MustMatchers, WordSpec}

import scala.concurrent.duration._

class HostResolverSpec
    extends WordSpec
    with MustMatchers
    with WSProxyKafkaSpec {

  implicit val cfg = defaultTestAppCfg.copy(
    kafkaClient = defaultTestAppCfg.kafkaClient.copy(
      brokerResolutionTimeout = 5 seconds
    )
  )

  "The HostResolver" should {

    "fail resolution after retrying an action for a given duration" in {
      val hosts = KafkaBootstrapHosts(List("foo"))
      resolveKafkaBootstrapHosts(hosts).hasErrors mustBe true
    }

    "successfully resolve a host" in {
      val hosts = KafkaBootstrapHosts(List("github.com"))
      resolveKafkaBootstrapHosts(hosts).hasErrors mustBe false
    }

    "resolve some hosts and fail others" in {
      val hosts = KafkaBootstrapHosts(
        List("github.com", "foo", "uggabugga", "gitlab.com", "google.com")
      )
      val res = resolveKafkaBootstrapHosts(hosts)
      res.hasErrors mustBe true
      res.results.count(_.isError) mustBe 2
      res.results.count(_.isSuccess) mustBe 3
    }

  }

}
