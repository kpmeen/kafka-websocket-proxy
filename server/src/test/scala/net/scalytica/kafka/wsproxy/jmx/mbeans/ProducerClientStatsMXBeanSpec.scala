package net.scalytica.kafka.wsproxy.jmx.mbeans

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.scaladsl.adapter._
import net.scalytica.kafka.wsproxy.jmx.mbeans.ProducerClientStatsProtocol._
import net.scalytica.kafka.wsproxy.jmx.{producerStatsName, TestJmxQueries}
import net.scalytica.kafka.wsproxy.models.{
  FullProducerId,
  WsProducerId,
  WsProducerInstanceId
}
import net.scalytica.test.WsProxyKafkaSpec
import org.scalatest.{BeforeAndAfterAll, OptionValues}
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.duration._

class ProducerClientStatsMXBeanSpec
    extends AnyWordSpec
    with Matchers
    with OptionValues
    with BeforeAndAfterAll
    with WsProxyKafkaSpec {

  val tk   = ActorTestKit(system.toTyped)
  val jmxq = new TestJmxQueries

  val pid       = WsProducerId("test")
  val iid       = WsProducerInstanceId("instance")
  val fid       = FullProducerId(pid, Option(iid))
  val fidNoInst = FullProducerId(pid, None)

  val beanName1 = producerStatsName(fid)
  val beanName2 = producerStatsName(fidNoInst)

  val behavior1 = ProducerClientStatsMXBeanActor(
    fullProducerId = fid,
    useAutoAggregation = false
  )

  val behavior2 = ProducerClientStatsMXBeanActor(
    fullProducerId = fidNoInst,
    useAutoAggregation = false
  )

  override protected def afterAll(): Unit = {
    super.afterAll()
    jmxq.removeMxBean(beanName1, classOf[ProducerClientStatsMXBean])
    jmxq.removeMxBean(beanName2, classOf[ProducerClientStatsMXBean])
    tk.shutdownTestKit()
  }

  "The ProducerClientStatsMxBean" when {

    "provided with both producerId and instanceId" should {
      "increment the bean counters" in {
        val ref   = tk.spawn(behavior1, beanName1)
        val probe = tk.createTestProbe[ProducerClientStatsResponse]("pcsr1")
        val proxy =
          jmxq.getMxBean(beanName1, classOf[ProducerClientStatsMXBean])

        ref ! IncrementRecordsReceived(probe.ref)
        probe.expectMessage(1 second, RecordsReceivedIncremented)
        proxy.getNumRecordsReceivedTotal mustBe 1

        ref ! IncrementAcksSent(probe.ref)
        probe.expectMessage(1 second, AcksSentIncremented)
        proxy.getNumAcksSentTotal mustBe 1
      }
    }

    "provided with only producerId" should {
      "increment the bean counters" in {
        val ref   = tk.spawn(behavior2, beanName2)
        val probe = tk.createTestProbe[ProducerClientStatsResponse]("pcsr2")
        val proxy =
          jmxq.getMxBean(beanName2, classOf[ProducerClientStatsMXBean])

        ref ! IncrementRecordsReceived(probe.ref)
        probe.expectMessage(1 second, RecordsReceivedIncremented)
        proxy.getNumRecordsReceivedTotal mustBe 1

        ref ! IncrementAcksSent(probe.ref)
        probe.expectMessage(1 second, AcksSentIncremented)
        proxy.getNumAcksSentTotal mustBe 1
      }
    }
  }
}
