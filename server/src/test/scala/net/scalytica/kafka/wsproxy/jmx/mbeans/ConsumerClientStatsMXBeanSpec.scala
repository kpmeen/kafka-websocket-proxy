package net.scalytica.kafka.wsproxy.jmx.mbeans

import net.scalytica.kafka.wsproxy.jmx.consumerStatsName
import net.scalytica.kafka.wsproxy.jmx.mbeans.ConsumerClientStatsProtocol._
import net.scalytica.kafka.wsproxy.models.{
  FullConsumerId,
  WsClientId,
  WsGroupId
}
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe
import org.apache.pekko.actor.typed.{ActorRef, Behavior}

import scala.concurrent.duration._

// scalastyle:off magic.number
class ConsumerClientStatsMXBeanSpec extends MXBeanSpecLike {

  val cid: WsClientId     = WsClientId("bean-spec")
  val gid: WsGroupId      = WsGroupId("bean-spec")
  val fid: FullConsumerId = FullConsumerId(gid, cid)
  val beanName: String    = consumerStatsName(fid)

  val behavior: Behavior[ConsumerClientStatsCommand] =
    ConsumerClientStatsMXBeanActor(
      fullConsumerId = fid,
      useAutoAggregation = false
    )

  val ref: ActorRef[ConsumerClientStatsCommand] =
    tk.spawn(behavior, beanName)

  val probe: TestProbe[ConsumerClientStatsResponse] =
    tk.createTestProbe[ConsumerClientStatsResponse]("bean-spec-ccsr-probe")

  val proxy: ConsumerClientStatsMXBean =
    jmxq.getMxBean(beanName, classOf[ConsumerClientStatsMXBean])

  override protected def afterAll(): Unit = {
    super.afterAll()
  }

  "The ConsumerClientStatsMXBean" should {

    "increment number of records sent" in {
      ref ! IncrementRecordSent(probe.ref)
      probe.expectMessage(1 second, RecordsSentIncremented)
      proxy.getNumRecordsSentTotal mustBe 1
      proxy.getNumUncommittedRecords mustBe 1
    }

    "increment number of commits received" in {
      ref ! IncrementCommitsReceived(probe.ref)
      probe.expectMessage(1 second, CommitsReceivedIncremented)
      proxy.getNumCommitsReceivedTotal mustBe 1
      proxy.getNumUncommittedRecords mustBe 0
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

    "stop the MXBean" in {
      ref ! Stop
      probe.expectTerminated(ref)
      jmxq.findByTypeAndName[ConsumerClientStatsMXBean](
        beanName
      ) mustBe None
    }

  }
}
