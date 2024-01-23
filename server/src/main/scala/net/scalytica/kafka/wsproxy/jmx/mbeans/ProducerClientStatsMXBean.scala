package net.scalytica.kafka.wsproxy.jmx.mbeans

import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors}
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import net.scalytica.kafka.wsproxy.jmx.MXBeanActor
import net.scalytica.kafka.wsproxy.jmx.mbeans.ProducerClientStatsProtocol._
import net.scalytica.kafka.wsproxy.models.FullProducerId

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration._

trait ProducerClientStatsMXBean extends WsProxyJmxBean {

  def getProducerId: String
  def getInstanceId: String
  def getFullId: String

  def getNumRecordsReceivedTotal: Long
  def getNumRecordsReceivedLastHour: Long
  def getNumRecordsReceivedLastMinute: Long

  def getNumAcksSentTotal: Long
  def getNumAcksSentLastHour: Long
  def getNumAcksSentLastMinute: Long

}

class ProducerClientStatsMXBeanActor(
    ctx: ActorContext[ProducerClientStatsCommand],
    fullProducerId: FullProducerId,
    useAutoAggregation: Boolean
) extends MXBeanActor[ProducerClientStatsCommand](ctx)
    with ProducerClientStatsMXBean {

  override def getProducerId: String = {
    fullProducerId.producerId.value
  }

  override def getInstanceId: String =
    fullProducerId.instanceId
      .map(_.value)
      .getOrElse(fullProducerId.uuid.toString)

  override def getFullId: String = fullProducerId.value

  @volatile private[this] var numSentTotal: Long   = 0
  @volatile private[this] var numSentHour: Long    = 0
  @volatile private[this] var numSentMinute: Long  = 0
  @volatile private[this] var currSentHour: Long   = 0
  @volatile private[this] var currSentMinute: Long = 0

  @volatile private[this] var numRecTotal: Long   = 0
  @volatile private[this] var numRecHour: Long    = 0
  @volatile private[this] var numRecMinute: Long  = 0
  @volatile private[this] var currRecHour: Long   = 0
  @volatile private[this] var currRecMinute: Long = 0

  implicit val ec: ExecutionContextExecutor = ctx.executionContext
  private[this] def ignore[T]               = ctx.system.ignoreRef[T]

  if (useAutoAggregation) {
    // Schedule minute based updates of the time based counters
    ctx.system.scheduler.scheduleAtFixedRate(1 minute, 1 minute) { () =>
      ctx.self.tell(UpdateRecordsPerMinute(currRecMinute, ignore))
      ctx.self.tell(UpdateAcksPerMinute(currSentMinute, ignore))
      currSentMinute = 0
      currRecMinute = 0
    }

    // Schedule hour based updates of the time based counters
    ctx.system.scheduler.scheduleAtFixedRate(1 hour, 1 hour) { () =>
      ctx.self.tell(UpdateRecordsPerHour(currRecMinute, ignore))
      ctx.self.tell(UpdateAcksPerHour(currSentMinute, ignore))
      currRecHour = 0
      currSentHour = 0
    }
  }

  private[this] def incrementRecCounters(): Unit = {
    numRecTotal = numRecTotal + 1
    currRecHour = currRecHour + 1
    currRecMinute = currRecMinute + 1
  }

  private[this] def incrementAckCounters(): Unit = {
    numSentTotal = numSentTotal + 1
    currSentHour = currSentHour + 1
    currSentMinute = currSentMinute + 1
  }

  override def getNumRecordsReceivedTotal: Long      = numRecTotal
  override def getNumRecordsReceivedLastHour: Long   = numRecHour
  override def getNumRecordsReceivedLastMinute: Long = numRecMinute
  override def getNumAcksSentTotal: Long             = numSentTotal
  override def getNumAcksSentLastHour: Long          = numSentHour
  override def getNumAcksSentLastMinute: Long        = numSentMinute

  override def onMessage(
      msg: ProducerClientStatsCommand
  ): Behavior[ProducerClientStatsCommand] = {
    msg match {
      case IncrementRecordsReceived(replyTo) =>
        doAndSame { () =>
          incrementRecCounters()
          replyTo ! RecordsReceivedIncremented
        }

      case UpdateRecordsPerHour(n, replyTo) =>
        doAndSame { () =>
          numRecHour = n
          replyTo ! RecordsPerHourUpdated
        }

      case UpdateRecordsPerMinute(n, replyTo) =>
        doAndSame { () =>
          numRecMinute = n
          replyTo ! RecordsPerMinuteUpdated
        }

      case IncrementAcksSent(replyTo) =>
        doAndSame { () =>
          incrementAckCounters()
          replyTo ! AcksSentIncremented
        }

      case UpdateAcksPerHour(n, replyTo) =>
        doAndSame { () =>
          numSentHour = n
          replyTo ! AcksPerHourUpdated
        }

      case UpdateAcksPerMinute(n, replyTo) =>
        doAndSame { () =>
          numSentMinute = n
          replyTo ! AcksPerMinuteUpdated
        }

      case Stop =>
        Behaviors.stopped
    }
  }
}

object ProducerClientStatsMXBeanActor {

  def apply(
      fullProducerId: FullProducerId
  ): Behavior[ProducerClientStatsCommand] =
    Behaviors.setup { ctx =>
      new ProducerClientStatsMXBeanActor(
        ctx = ctx,
        fullProducerId = fullProducerId,
        useAutoAggregation = true
      )
    }

  def apply(
      fullProducerId: FullProducerId,
      useAutoAggregation: Boolean
  ): Behavior[ProducerClientStatsCommand] =
    Behaviors.setup { ctx =>
      new ProducerClientStatsMXBeanActor(
        ctx = ctx,
        fullProducerId = fullProducerId,
        useAutoAggregation = useAutoAggregation
      )
    }

}

object ProducerClientStatsProtocol {

  sealed trait ProducerClientStatsCommand
  sealed trait ProducerClientStatsResponse

  case class IncrementRecordsReceived(
      replyTo: ActorRef[ProducerClientStatsResponse]
  ) extends ProducerClientStatsCommand

  case class UpdateRecordsPerHour(
      n: Long,
      replyTo: ActorRef[ProducerClientStatsResponse]
  ) extends ProducerClientStatsCommand

  case class UpdateRecordsPerMinute(
      n: Long,
      replyTo: ActorRef[ProducerClientStatsResponse]
  ) extends ProducerClientStatsCommand

  case class IncrementAcksSent(replyTo: ActorRef[ProducerClientStatsResponse])
      extends ProducerClientStatsCommand

  case class UpdateAcksPerHour(
      n: Long,
      replyTo: ActorRef[ProducerClientStatsResponse]
  ) extends ProducerClientStatsCommand

  case class UpdateAcksPerMinute(
      n: Long,
      replyTo: ActorRef[ProducerClientStatsResponse]
  ) extends ProducerClientStatsCommand

  case object Stop extends ProducerClientStatsCommand

  case object RecordsReceivedIncremented extends ProducerClientStatsResponse
  case object RecordsPerHourUpdated      extends ProducerClientStatsResponse
  case object RecordsPerMinuteUpdated    extends ProducerClientStatsResponse
  case object AcksSentIncremented        extends ProducerClientStatsResponse
  case object AcksPerHourUpdated         extends ProducerClientStatsResponse
  case object AcksPerMinuteUpdated       extends ProducerClientStatsResponse
}
