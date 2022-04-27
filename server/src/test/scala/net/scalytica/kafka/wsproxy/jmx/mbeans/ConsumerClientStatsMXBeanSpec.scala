package net.scalytica.kafka.wsproxy.jmx.mbeans

import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.actor.typed.scaladsl.adapter._
import net.scalytica.kafka.wsproxy.jmx.mbeans.ConsumerClientStatsProtocol._
import net.scalytica.kafka.wsproxy.jmx.{consumerStatsName, TestJmxQueries}
import net.scalytica.kafka.wsproxy.models.{
  FullConsumerId,
  WsClientId,
  WsGroupId
}
import net.scalytica.test.WsProxyKafkaSpec
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{BeforeAndAfterAll, OptionValues}

import scala.concurrent.duration._

class ConsumerClientStatsMXBeanSpec
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
  val gid      = WsGroupId("test")
  val fid      = FullConsumerId(gid, cid)
  val beanName = consumerStatsName(fid)

  val behavior = ConsumerClientStatsMXBeanActor(
    fullConsumerId = fid,
    useAutoAggregation = false
  )

  val ref   = testKit.spawn(behavior, beanName)
  val probe = testKit.createTestProbe[ConsumerClientStatsResponse]("ccsr-probe")
  val proxy = jmxq.getMxBean(beanName, classOf[ConsumerClientStatsMXBean])

  "The ConsumerClientStatsMXBean" should {

    "increment number of records sent" in {
      ref ! IncrementRecordSent(probe.ref)
      probe.expectMessage(1 second, RecordsSentIncremented)
      proxy.getNumRecordsSentTotal mustBe 1
    }

    "increment number of commits received" in {
      ref ! IncrementCommitsReceived(probe.ref)
      probe.expectMessage(1 second, CommitsReceivedIncremented)
      proxy.getNumCommitsReceivedTotal mustBe 1
    }

    "aggregate number of records for the last minute" in {
      ref ! UpdateRecordsPerMinute(proxy.getNumRecordsSentTotal, probe.ref)
      probe.expectMessage(1 second, RecordsPerMinuteUpdated)
      proxy.getNumRecordsSentTotal mustBe 1
      proxy.getNumRecordsSentLastMinute mustBe 1
    }

    "aggregate number of commits for the last minute" in {
      ref ! UpdateCommitsPerMinute(proxy.getNumCommitsReceivedTotal, probe.ref)
      probe.expectMessage(1 second, CommitsPerMinuteUpdated)
      proxy.getNumCommitsReceivedTotal mustBe 1
      proxy.getNumCommitsReceivedLastMinute mustBe 1
    }

    "aggregate number of records for the last hour" in {
      ref ! UpdateRecordsPerHour(1, probe.ref)
      probe.expectMessage(1 second, RecordsPerHourUpdated)
      proxy.getNumRecordsSentLastHour mustBe 1
    }

    "aggregate number of commits for the last hour" in {
      ref ! UpdateCommitsPerHour(1, probe.ref)
      probe.expectMessage(1 second, CommitsPerHourUpdated)
      proxy.getNumCommitsReceivedLastHour mustBe 1
    }

    "update number of uncommitted records" in {
      ref ! UpdateUncommitted(1, probe.ref)
      probe.expectMessage(1 second, UncommittedUpdated)
      proxy.getNumCommitsReceivedTotal mustBe 1
    }

  }
}
