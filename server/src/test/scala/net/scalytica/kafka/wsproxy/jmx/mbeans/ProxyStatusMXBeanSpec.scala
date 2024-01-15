package net.scalytica.kafka.wsproxy.jmx.mbeans

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.scaladsl.adapter._
import net.scalytica.kafka.wsproxy.jmx.TestJmxQueries
import net.scalytica.kafka.wsproxy.jmx.mbeans.ProxyStatusProtocol._
import net.scalytica.kafka.wsproxy.models.BrokerInfo
import net.scalytica.test.WsProxyKafkaSpec
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{BeforeAndAfterAll, OptionValues}

import scala.concurrent.duration._

// scalastyle:off magic.number
class ProxyStatusMXBeanSpec
    extends AnyWordSpec
    with Matchers
    with OptionValues
    with BeforeAndAfterAll
    with WsProxyKafkaSpec {

  val testKit = ActorTestKit(system.toTyped)

  override protected def afterAll(): Unit = {
    super.afterAll()
    testKit.shutdownTestKit()
  }

  val brokerInfoList = List(
    BrokerInfo(1, "host1", 1234, None),
    BrokerInfo(2, "host2", 5678, Some("rack2"))
  )

  "The ProxyStatusMXBean" should {

    "contain all relevant configuration data" in {
      val jmxq   = new TestJmxQueries
      val appCfg = plainTestConfig()

      val beanName = "test-wsproxy-status-a"
      val psaRef   = testKit.spawn(ProxyStatusMXBeanActor(appCfg), beanName)
      val probe =
        testKit.createTestProbe[ProxyStatusResponse]()

      psaRef ! UpdateKafkaClusterInfo(brokerInfoList, probe.ref)

      // Ensure the actor is up and running and registered in the MBean registry
      // before continuing with the test
      probe.expectMessage(5 seconds, KafkaClusterInfoUpdated)

      val psb = jmxq.getMxBean(beanName, classOf[ProxyStatusMXBean])

      psb.isHttpEnabled mustBe true
      psb.isHttpsEnabled mustBe false
      psb.isBasicAuthEnabled mustBe false
      psb.isOpenIDConnectEnabled mustBe false
      psb.getHttpPort mustBe 8078
      psb.getHttpsPort mustBe 0
      psb.getSessionStateTopicName mustBe "_wsproxy.session.state"
      psb.getUpSince must not be empty
      psb.getUptimeMillis mustBe >(0L)
      psb.getBrokerInfoListMXView.brokers must have size 2
      val b1 = psb.getBrokerInfoListMXView.brokers.headOption.value
      b1.id mustBe 1
      b1.host mustBe "host1"
      b1.port mustBe 1234
      b1.rack mustBe null // scalastyle:ignore

      val b2 = psb.getBrokerInfoListMXView.brokers.lastOption.value
      b2.id mustBe 2
      b2.host mustBe "host2"
      b2.port mustBe 5678
      b2.rack mustBe "rack2"

      testKit.shutdownTestKit()
    }

  }

}
// scalastyle:on
