package net.scalytica.kafka.wsproxy.jmx.mbeans

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import net.scalytica.kafka.wsproxy.jmx.MXBeanActor
import net.scalytica.kafka.wsproxy.jmx.mbeans.ConsumerClientStatsProtocol._
import net.scalytica.kafka.wsproxy.models.{WsClientId, WsGroupId}

import scala.concurrent.duration._

trait ConsumerClientStatsMXBean extends WsProxyJmxBean {

  def getClientId: String
  def getGroupId: String

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
    cid: WsClientId,
    gid: WsGroupId,
    useAutoAggregation: Boolean
) extends MXBeanActor[ConsumerClientStatsCommand](ctx)
    with ConsumerClientStatsMXBean {

  @volatile private[this] var numSentTotal: Long   = 0
  @volatile private[this] var numSentHr: Long      = 0
  @volatile private[this] var numSentMin: Long     = 0
  @volatile private[this] var currSentHour: Long   = 0
  @volatile private[this] var currSentMinute: Long = 0

  @volatile private[this] var numRecTotal: Long   = 0
  @volatile private[this] var numRecHr: Long      = 0
  @volatile private[this] var numRecMin: Long     = 0
  @volatile private[this] var currRecHour: Long   = 0
  @volatile private[this] var currRecMinute: Long = 0

  @volatile private[this] var uncommittedTotal: Long = 0

  override def getClientId = cid.value
  override def getGroupId  = gid.value

  override def getNumRecordsSentTotal      = numSentTotal
  override def getNumRecordsSentLastHour   = numSentHr
  override def getNumRecordsSentLastMinute = numSentMin

  override def getNumCommitsReceivedTotal      = numRecTotal
  override def getNumCommitsReceivedLastHour   = numRecHr
  override def getNumCommitsReceivedLastMinute = numRecMin

  override def getNumUncommittedRecords = uncommittedTotal

  implicit val ec             = ctx.executionContext
  private[this] def ignore[T] = ctx.system.ignoreRef[T]

  if (useAutoAggregation) {
    // Schedule minute based updates of the time based counters
    ctx.system.scheduler.scheduleAtFixedRate(1 minute, 1 minute) { () =>
      ctx.self.tell(UpdateCommitsPerMinute(currRecMinute, ignore))
      ctx.self.tell(UpdateRecordsPerMinute(currSentMinute, ignore))
      currSentMinute = 0
      currRecMinute = 0
    }

    // Schedule hour based updates of the time based counters
    ctx.system.scheduler.scheduleAtFixedRate(1 hour, 1 hour) { () =>
      ctx.self.tell(UpdateRecordsPerHour(currSentMinute, ignore))
      ctx.self.tell(UpdateCommitsPerHour(currRecMinute, ignore))
      currRecHour = 0
      currSentHour = 0
    }
  }

  private[this] def incrementRecCounters(): Unit = {
    numRecTotal = numRecTotal + 1
    currRecHour = currRecHour + 1
    currRecMinute = currRecMinute + 1
  }

  private[this] def incrementSentCounters(): Unit = {
    numSentTotal = numSentTotal + 1
    currSentHour = currSentHour + 1
    currSentMinute = currSentMinute + 1
  }

  override def onMessage(msg: ConsumerClientStatsCommand) = {
    msg match {
      case IncrementRecordSent(replyTo) =>
        doAndSame { () =>
          incrementSentCounters()
          replyTo ! RecordsSentIncremented
        }

      case UpdateRecordsPerHour(n, replyTo) =>
        doAndSame { () =>
          numSentHr = n
          replyTo ! RecordsPerHourUpdated
        }

      case UpdateRecordsPerMinute(n, replyTo) =>
        doAndSame { () =>
          numSentMin = n
          replyTo ! RecordsPerMinuteUpdated
        }

      case IncrementCommitsReceived(replyTo) =>
        doAndSame { () =>
          incrementRecCounters()
          replyTo ! CommitsReceivedIncremented
        }

      case UpdateCommitsPerHour(n, replyTo) =>
        doAndSame { () =>
          numRecHr = n
          replyTo ! CommitsPerHourUpdated
        }

      case UpdateCommitsPerMinute(n, replyTo) =>
        doAndSame { () =>
          numRecMin = n
          replyTo ! CommitsPerMinuteUpdated
        }

      case UpdateUncommitted(n, replyTo) =>
        doAndSame { () =>
          uncommittedTotal = n
          replyTo ! UncommittedUpdated
        }

      case Stop =>
        Behaviors.stopped
    }
  }

}

object ConsumerClientStatsMXBeanActor {

  def apply(
      clientId: WsClientId,
      groupId: WsGroupId
  ): Behavior[ConsumerClientStatsCommand] = Behaviors.setup { ctx =>
    new ConsumerClientStatsMXBeanActor(
      ctx = ctx,
      cid = clientId,
      gid = groupId,
      useAutoAggregation = true
    )
  }

  def apply(
      clientId: WsClientId,
      groupId: WsGroupId,
      useAutoAggregation: Boolean
  ): Behavior[ConsumerClientStatsCommand] = Behaviors.setup { ctx =>
    new ConsumerClientStatsMXBeanActor(
      ctx = ctx,
      cid = clientId,
      gid = groupId,
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

  case class UpdateUncommitted(
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
  case object UncommittedUpdated         extends ConsumerClientStatsResponse
}
