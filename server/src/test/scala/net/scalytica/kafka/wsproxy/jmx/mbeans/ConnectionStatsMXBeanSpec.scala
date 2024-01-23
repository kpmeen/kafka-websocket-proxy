package net.scalytica.kafka.wsproxy.jmx.mbeans

import net.scalytica.kafka.wsproxy.jmx.mbeans.ConnectionsStatsProtocol._
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe
import org.apache.pekko.actor.typed.ActorRef

import scala.concurrent.duration._

// scalastyle:off magic.number
class ConnectionStatsMXBeanSpec extends MXBeanSpecLike {

  val beanName = "bean-spec-wsproxy-connections"

  val ref: ActorRef[ConnectionsStatsCommand] =
    tk.spawn(ConnectionsStatsMXBeanActor(), beanName)

  val probe: TestProbe[ConnectionStatsResponse] =
    tk.createTestProbe[ConnectionStatsResponse]("bean-spec-stats-probe")

  val proxy: ConnectionsStatsMXBean =
    jmxq.getMxBean(beanName, classOf[ConnectionsStatsMXBean])

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

    "stop the MXBean" in {
      ref ! Stop
      probe.expectTerminated(ref)
      jmxq.findByTypeAndName[ConnectionsStatsMXBean](beanName) mustBe None
    }

  }
}
