package net.scalytica.kafka.wsproxy.jmx.mbeans

import org.apache.pekko.actor.typed.scaladsl.adapter._
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import net.scalytica.kafka.wsproxy.jmx.TestJmxQueries
import net.scalytica.kafka.wsproxy.jmx.mbeans.ConnectionsStatsProtocol._
import net.scalytica.test.WsProxyKafkaSpec
import org.scalatest.{BeforeAndAfterAll, OptionValues}
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.duration._

class ConnectionStatsMXBeanSpec
    extends AnyWordSpec
    with Matchers
    with OptionValues
    with BeforeAndAfterAll
    with WsProxyKafkaSpec {

  val testKit: ActorTestKit = ActorTestKit(system.toTyped)

  override protected def afterAll(): Unit = {
    super.afterAll()
    testKit.shutdownTestKit()
  }

  val jmxq     = new TestJmxQueries
  val beanName = "test-wsproxy-connections"
  val ref      = testKit.spawn(ConnectionsStatsMXBeanActor(), beanName)
  val probe    = testKit.createTestProbe[ConnectionStatsResponse]("stats-probe")

  val proxy = jmxq.getMxBean(beanName, classOf[ConnectionsStatsMXBean])

  "The ConnectionStatsMXBean" should {

    "increment number of open producer connections" in {
      ref ! AddProducer(probe.ref)
      probe.expectMessage(1 second, ProducerAdded)
      proxy.getOpenWebSocketsProducers mustBe 1
      proxy.getOpenWebSocketsTotal mustBe 1
    }

    "increment number of open consumer connections" in {
      ref ! AddConsumer(probe.ref)
      probe.expectMessage(1 second, ConsumerAdded)
      proxy.getOpenWebSocketsConsumers mustBe 1
      proxy.getOpenWebSocketsTotal mustBe 2
    }

    "decrement number of open producer connections" in {
      ref ! RemoveProducer(probe.ref)
      probe.expectMessage(1 second, ProducerRemoved)
      proxy.getOpenWebSocketsProducers mustBe 0
      proxy.getOpenWebSocketsTotal mustBe 1
    }

    "decrement number of open consumer connections" in {
      ref ! RemoveConsumer(probe.ref)
      probe.expectMessage(1 second, ConsumerRemoved)
      proxy.getOpenWebSocketsConsumers mustBe 0
      proxy.getOpenWebSocketsTotal mustBe 0
    }

  }
}
