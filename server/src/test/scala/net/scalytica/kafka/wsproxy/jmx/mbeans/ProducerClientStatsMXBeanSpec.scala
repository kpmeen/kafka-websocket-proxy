package net.scalytica.kafka.wsproxy.jmx.mbeans

import net.scalytica.kafka.wsproxy.jmx.mbeans.ProducerClientStatsProtocol._
import net.scalytica.kafka.wsproxy.jmx.producerStatsName
import net.scalytica.kafka.wsproxy.models.{
  FullProducerId,
  WsProducerId,
  WsProducerInstanceId
}
import org.apache.pekko.actor.typed.Behavior
import org.scalatest.Assertion

import scala.concurrent.duration._

// scalastyle:off magic.number
class ProducerClientStatsMXBeanSpec extends MXBeanSpecLike {

  val pid: WsProducerId         = WsProducerId("bean-spec")
  val iid: WsProducerInstanceId = WsProducerInstanceId("bean-spec-instance")
  val fid: FullProducerId       = FullProducerId(pid, Option(iid))
  val fidNoInst: FullProducerId = FullProducerId(pid, None)

  val beanName1: String = producerStatsName(fid)
  val beanName2: String = producerStatsName(fidNoInst)

  val behavior1: Behavior[ProducerClientStatsCommand] =
    ProducerClientStatsMXBeanActor(
      fullProducerId = fid,
      useAutoAggregation = false
    )

  val behavior2: Behavior[ProducerClientStatsCommand] =
    ProducerClientStatsMXBeanActor(
      fullProducerId = fidNoInst,
      useAutoAggregation = false
    )

  def jmxContext[T](
      beanName: String,
      behavior: Behavior[ProducerClientStatsCommand]
  ): Assertion = {
    val ref = tk.spawn(behavior, beanName)
    val probe =
      tk.createTestProbe[ProducerClientStatsResponse](s"$beanName-probe")

    val proxy = jmxq.getMxBean(beanName, classOf[ProducerClientStatsMXBean])

    ref ! IncrementRecordsReceived(probe.ref)
    probe.expectMessage(1 second, RecordsReceivedIncremented)
    ref ! UpdateRecordsPerHour(1, probe.ref)
    probe.expectMessage(1 second, RecordsPerHourUpdated)
    ref ! UpdateRecordsPerMinute(1, probe.ref)
    probe.expectMessage(1 second, RecordsPerMinuteUpdated)

    proxy.getNumRecordsReceivedTotal mustBe 1
    proxy.getNumRecordsReceivedLastMinute mustBe 1
    proxy.getNumRecordsReceivedLastHour mustBe 1

    ref ! IncrementAcksSent(probe.ref)
    probe.expectMessage(1 second, AcksSentIncremented)
    ref ! UpdateAcksPerHour(1, probe.ref)
    probe.expectMessage(1 second, AcksPerHourUpdated)
    ref ! UpdateAcksPerMinute(1, probe.ref)
    probe.expectMessage(1 second, AcksPerMinuteUpdated)

    proxy.getNumAcksSentTotal mustBe 1
    proxy.getNumAcksSentLastMinute mustBe 1
    proxy.getNumAcksSentLastHour mustBe 1

    ref ! Stop
    probe.expectTerminated(ref)

    jmxq.findByTypeAndName[ProducerClientStatsMXBean](beanName) mustBe None
  }

  "The ProducerClientStatsMxBean" when {

    "provided with both producerId and instanceId" should {
      "increment the bean counters" in jmxContext(beanName1, behavior1)
    }

    "provided with only producerId" should {
      "increment the bean counters" in jmxContext(beanName2, behavior2)
    }
  }
}
