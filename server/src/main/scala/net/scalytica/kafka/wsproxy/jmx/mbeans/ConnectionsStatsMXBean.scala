package net.scalytica.kafka.wsproxy.jmx.mbeans

import net.scalytica.kafka.wsproxy.jmx.MXBeanActor
import net.scalytica.kafka.wsproxy.jmx.mbeans.ConnectionsStatsProtocol._

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors

trait ConnectionsStatsMXBean extends WsProxyJmxBean {

  def getOpenWebSocketsTotal: Int
  def getOpenWebSocketsProducers: Int
  def getOpenWebSocketsConsumers: Int

}

class ConnectionsStatsMXBeanActor(ctx: ActorContext[ConnectionsStatsCommand])
    extends MXBeanActor[ConnectionsStatsCommand](ctx)
    with ConnectionsStatsMXBean {

  // These variables are volatile because JMX and the actor model access from
  // different threads during runtime.
  @volatile private[this] var total: Int     = 0
  @volatile private[this] var consumers: Int = 0
  @volatile private[this] var producers: Int = 0

  private[this] def incrementTotal(): Unit = total = total + 1
  private[this] def decrementTotal(): Unit = total = total - 1

  private[this] def incrementConsumers(): Unit = {
    consumers = consumers + 1
    incrementTotal()
  }

  private[this] def decrementConsumers(): Unit = {
    consumers = consumers - 1
    decrementTotal()
  }

  private[this] def incrementProducers(): Unit = {
    producers = producers + 1
    incrementTotal()
  }

  private[this] def decrementProducers(): Unit = {
    producers = producers - 1
    decrementTotal()
  }

  override def onMessage(
      msg: ConnectionsStatsCommand
  ): Behavior[ConnectionsStatsCommand] = {
    msg match {
      case AddProducer(replyTo) =>
        doAndSame { () =>
          incrementProducers()
          replyTo ! ProducerAdded
        }

      case RemoveProducer(replyTo) =>
        doAndSame { () =>
          decrementProducers()
          replyTo ! ProducerRemoved
        }

      case AddConsumer(replyTo) =>
        doAndSame { () =>
          incrementConsumers()
          replyTo ! ConsumerAdded
        }

      case RemoveConsumer(replyTo) =>
        doAndSame { () =>
          decrementConsumers()
          replyTo ! ConsumerRemoved
        }

      case Stop =>
        Behaviors.stopped
    }
  }

  override def getOpenWebSocketsTotal: Int     = total
  override def getOpenWebSocketsProducers: Int = producers
  override def getOpenWebSocketsConsumers: Int = consumers
}

object ConnectionsStatsMXBeanActor {

  def apply(): Behavior[ConnectionsStatsCommand] =
    Behaviors.setup(ctx => new ConnectionsStatsMXBeanActor(ctx))

}

object ConnectionsStatsProtocol {

  sealed trait ConnectionsStatsCommand
  sealed trait ConnectionStatsResponse

  case class AddProducer(replyTo: ActorRef[ConnectionStatsResponse])
      extends ConnectionsStatsCommand

  case class RemoveProducer(replyTo: ActorRef[ConnectionStatsResponse])
      extends ConnectionsStatsCommand

  case class AddConsumer(replyTo: ActorRef[ConnectionStatsResponse])
      extends ConnectionsStatsCommand

  case class RemoveConsumer(replyTo: ActorRef[ConnectionStatsResponse])
      extends ConnectionsStatsCommand

  case object Stop extends ConnectionsStatsCommand

  case object ProducerAdded   extends ConnectionStatsResponse
  case object ProducerRemoved extends ConnectionStatsResponse
  case object ConsumerAdded   extends ConnectionStatsResponse
  case object ConsumerRemoved extends ConnectionStatsResponse
}
