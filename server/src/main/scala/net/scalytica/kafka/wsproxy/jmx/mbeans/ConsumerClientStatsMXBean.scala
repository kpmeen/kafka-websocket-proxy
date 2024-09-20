package net.scalytica.kafka.wsproxy.jmx.mbeans

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration._

import net.scalytica.kafka.wsproxy.jmx.MXBeanActor
import net.scalytica.kafka.wsproxy.jmx.mbeans.ConsumerClientStatsProtocol._
import net.scalytica.kafka.wsproxy.models.FullConsumerId

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors

trait ConsumerClientStatsMXBean extends WsProxyJmxBean {

  def getClientId: String
  def getGroupId: String
  def getFullId: String

  def getNumRecordsSentTotal: Long
  def getNumRecordsSentLastHour: Long
  def getNumRecordsSentLastMinute: Long

  def getNumCommitsReceivedTotal: Long
  def getNumCommitsReceivedLastHour: Long
  def getNumCommitsReceivedLastMinute: Long

  def getNumUncommittedRecords: Long
}

class ConsumerClientStatsMXBeanActor(
    ctx: ActorContext[ConsumerClientStatsCommand],
    fullConsumerId: FullConsumerId,
    useAutoAggregation: Boolean
) extends MXBeanActor[ConsumerClientStatsCommand](ctx)
    with ConsumerClientStatsMXBean {

  @volatile private[this] var numSentTotal: Long   = 0
  @volatile private[this] var numSentHour: Long    = 0
  @volatile private[this] var numSentMinute: Long  = 0
  @volatile private[this] var currSentHour: Long   = 0
  @volatile private[this] var currSentMinute: Long = 0

  @volatile private[this] var numCommitsTotal: Long   = 0
  @volatile private[this] var numCommitsHour: Long    = 0
  @volatile private[this] var numCommitsMinute: Long  = 0
  @volatile private[this] var currCommitsHour: Long   = 0
  @volatile private[this] var currCommitsMinute: Long = 0

  @volatile private[this] var numUncommittedTotal: Long = 0

  override def getClientId: String = fullConsumerId.clientId.value
  override def getGroupId: String  = fullConsumerId.groupId.value
  override def getFullId: String   = fullConsumerId.value

  override def getNumRecordsSentTotal: Long      = numSentTotal
  override def getNumRecordsSentLastHour: Long   = numSentHour
  override def getNumRecordsSentLastMinute: Long = numSentMinute

  override def getNumCommitsReceivedTotal: Long      = numCommitsTotal
  override def getNumCommitsReceivedLastHour: Long   = numCommitsHour
  override def getNumCommitsReceivedLastMinute: Long = numCommitsMinute

  override def getNumUncommittedRecords: Long = numUncommittedTotal

  implicit val ec: ExecutionContextExecutor = ctx.executionContext
  private[this] def ignore[T]: ActorRef[T]  = ctx.system.ignoreRef[T]

  if (useAutoAggregation) {
    // Schedule minute based updates of the time based counters
    ctx.system.scheduler.scheduleAtFixedRate(1 minute, 1 minute) { () =>
      ctx.self.tell(UpdateCommitsPerMinute(currCommitsMinute, ignore))
      ctx.self.tell(UpdateRecordsPerMinute(currSentMinute, ignore))
      currSentMinute = 0
      currCommitsMinute = 0
    }

    // Schedule hour based updates of the time based counters
    ctx.system.scheduler.scheduleAtFixedRate(1 hour, 1 hour) { () =>
      ctx.self.tell(UpdateRecordsPerHour(currSentMinute, ignore))
      ctx.self.tell(UpdateCommitsPerHour(currCommitsMinute, ignore))
      currCommitsHour = 0
      currSentHour = 0
    }
  }

  private[this] def incrementReceivedCounters(): Unit = {
    numCommitsTotal = numCommitsTotal + 1
    currCommitsHour = currCommitsHour + 1
    currCommitsMinute = currCommitsMinute + 1
    if (numUncommittedTotal > 0) numUncommittedTotal = numUncommittedTotal - 1
  }

  private[this] def incrementSentCounters(): Unit = {
    numSentTotal = numSentTotal + 1
    currSentHour = currSentHour + 1
    currSentMinute = currSentMinute + 1
    numUncommittedTotal = numUncommittedTotal + 1
  }

  override def onMessage(
      msg: ConsumerClientStatsCommand
  ): Behavior[ConsumerClientStatsCommand] = {
    msg match {
      case IncrementRecordSent(replyTo) =>
        doAndSame { () =>
          incrementSentCounters()
          replyTo ! RecordsSentIncremented
        }

      case UpdateRecordsPerHour(n, replyTo) =>
        doAndSame { () =>
          numSentHour = n
          replyTo ! RecordsPerHourUpdated
        }

      case UpdateRecordsPerMinute(n, replyTo) =>
        doAndSame { () =>
          numSentMinute = n
          replyTo ! RecordsPerMinuteUpdated
        }

      case IncrementCommitsReceived(replyTo) =>
        doAndSame { () =>
          incrementReceivedCounters()
          replyTo ! CommitsReceivedIncremented
        }

      case UpdateCommitsPerHour(n, replyTo) =>
        doAndSame { () =>
          numCommitsHour = n
          replyTo ! CommitsPerHourUpdated
        }

      case UpdateCommitsPerMinute(n, replyTo) =>
        doAndSame { () =>
          numCommitsMinute = n
          replyTo ! CommitsPerMinuteUpdated
        }

      case Stop =>
        Behaviors.stopped
    }
  }

}

object ConsumerClientStatsMXBeanActor {

  def apply(
      fullConsumerId: FullConsumerId
  ): Behavior[ConsumerClientStatsCommand] = Behaviors.setup { ctx =>
    new ConsumerClientStatsMXBeanActor(
      ctx = ctx,
      fullConsumerId = fullConsumerId,
      useAutoAggregation = true
    )
  }

  def apply(
      fullConsumerId: FullConsumerId,
      useAutoAggregation: Boolean
  ): Behavior[ConsumerClientStatsCommand] = Behaviors.setup { ctx =>
    new ConsumerClientStatsMXBeanActor(
      ctx = ctx,
      fullConsumerId = fullConsumerId,
      useAutoAggregation = useAutoAggregation
    )
  }

}

object ConsumerClientStatsProtocol {

  sealed trait ConsumerClientStatsCommand
  sealed trait ConsumerClientStatsResponse

  case class IncrementRecordSent(replyTo: ActorRef[ConsumerClientStatsResponse])
      extends ConsumerClientStatsCommand

  case class UpdateRecordsPerHour(
      n: Long,
      replyTo: ActorRef[ConsumerClientStatsResponse]
  ) extends ConsumerClientStatsCommand

  case class UpdateRecordsPerMinute(
      n: Long,
      replyTo: ActorRef[ConsumerClientStatsResponse]
  ) extends ConsumerClientStatsCommand

  case class IncrementCommitsReceived(
      replyTo: ActorRef[ConsumerClientStatsResponse]
  ) extends ConsumerClientStatsCommand

  case class UpdateCommitsPerHour(
      n: Long,
      replyTo: ActorRef[ConsumerClientStatsResponse]
  ) extends ConsumerClientStatsCommand

  case class UpdateCommitsPerMinute(
      n: Long,
      replyTo: ActorRef[ConsumerClientStatsResponse]
  ) extends ConsumerClientStatsCommand

  case object Stop extends ConsumerClientStatsCommand

  case object RecordsSentIncremented     extends ConsumerClientStatsResponse
  case object RecordsPerHourUpdated      extends ConsumerClientStatsResponse
  case object RecordsPerMinuteUpdated    extends ConsumerClientStatsResponse
  case object CommitsReceivedIncremented extends ConsumerClientStatsResponse
  case object CommitsPerHourUpdated      extends ConsumerClientStatsResponse
  case object CommitsPerMinuteUpdated    extends ConsumerClientStatsResponse
}
