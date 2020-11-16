package net.scalytica.kafka.wsproxy.jmx.mbeans

import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.actor.typed.scaladsl.adapter._
import net.scalytica.kafka.wsproxy.jmx.mbeans.ProducerClientStatsProtocol._
import net.scalytica.kafka.wsproxy.jmx.{producerStatsName, TestJmxQueries}
import net.scalytica.kafka.wsproxy.models.WsClientId
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

  val testKit = ActorTestKit(system.toTyped)

  override protected def afterAll(): Unit = {
    super.afterAll()
    testKit.shutdownTestKit()
  }

  val jmxq = new TestJmxQueries

  val cid      = WsClientId("test")
  val beanName = producerStatsName(cid)

  val behavior = ProducerClientStatsMXBeanActor(
    clientId = cid,
    useAutoAggregation = false
  )

  val ref   = testKit.spawn(behavior, beanName)
  val probe = testKit.createTestProbe[ProducerClientStatsResponse]("pcsr-probe")
  val proxy = jmxq.getMxBean(beanName, classOf[ProducerClientStatsMXBean])

  "The ProducerClientStatsMXBean" should {
    "increment the number records received" in {
      ref ! IncrementRecordsReceived(probe.ref)
      probe.expectMessage(1 second, RecordsReceivedIncremented)
      proxy.getNumRecordsReceivedTotal mustBe 1
    }

    "increment the number acks sent" in {
      ref ! IncrementAcksSent(probe.ref)
      probe.expectMessage(1 second, AcksSentIncremented)
      proxy.getNumAcksSentTotal mustBe 1
    }
  }
}
