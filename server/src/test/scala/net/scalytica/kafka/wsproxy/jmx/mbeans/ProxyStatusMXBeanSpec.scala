package net.scalytica.kafka.wsproxy.jmx.mbeans

import net.scalytica.kafka.wsproxy.jmx.mbeans.ProxyStatusProtocol._
import net.scalytica.kafka.wsproxy.models.BrokerInfo
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe
import org.apache.pekko.actor.typed.ActorRef

import scala.concurrent.duration._

// scalastyle:off magic.number
class ProxyStatusMXBeanSpec extends MXBeanSpecLike {

  override protected val testTopicPrefix: String = "mxbean-test-topic"

  val beanName = "bean-spec-wsproxy-status-a"

  val brokerInfoList: List[BrokerInfo] = List(
    BrokerInfo(1, "host1", 1234, None),
    BrokerInfo(2, "host2", 5678, Some("rack2"))
  )

  def jmxContext[T](
      body: (
          ActorRef[ProxyStatusCommand],
          TestProbe[ProxyStatusResponse]
      ) => T
  ): T = {
    val appCfg = plainTestConfig()
    val ref    = tk.spawn(ProxyStatusMXBeanActor(appCfg), beanName)
    val probe  = tk.createTestProbe[ProxyStatusResponse]()

    val res = body(ref, probe)

    ref ! Stop
    probe.expectTerminated(ref)

    jmxq.findByTypeAndName[ProxyStatusMXBean](beanName) mustBe None

    res
  }

  "The ProxyStatusMXBean" should {

    "contain all relevant configuration data" in jmxContext { (psaRef, probe) =>
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
    }

  }

}
// scalastyle:on
