package net.scalytica.kafka.wsproxy.utils

import net.scalytica.kafka.wsproxy.config.Configuration.KafkaBootstrapHosts
import net.scalytica.kafka.wsproxy.config.Configuration
import net.scalytica.kafka.wsproxy.utils.HostResolver.resolveKafkaBootstrapHosts
import net.scalytica.test.WsProxyKafkaSpec

import scala.concurrent.duration._
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec

class HostResolverSpec extends AnyWordSpec with Matchers with WsProxyKafkaSpec {

  implicit lazy val cfg: Configuration.AppCfg = defaultTestAppCfg.copy(
    kafkaClient = defaultTestAppCfg.kafkaClient.copy(
      brokerResolutionTimeout = 5 seconds,
      bootstrapHosts = KafkaBootstrapHosts(List("localhost"))
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
