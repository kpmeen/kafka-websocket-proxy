package net.scalytica.kafka.wsproxy.jmx

import javax.management.ObjectName

import net.scalytica.kafka.wsproxy.logging.WithProxyLogger

import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.PostStop
import org.apache.pekko.actor.typed.PreRestart
import org.apache.pekko.actor.typed.Signal
import org.apache.pekko.actor.typed.scaladsl.AbstractBehavior
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors

abstract class MXBeanActor[T](ctx: ActorContext[T])
    extends AbstractBehavior[T](ctx)
    with WithProxyLogger { self =>

  import WsProxyJmxRegistrar._

  val objectName: ObjectName =
    asObjectName(context.self.path.name, getMXTypeName)

  register()

  def getMXTypeName: String = mxBeanType(self.getClass)

  protected def register(): Unit = {
    registerToMBeanServer(this, objectName)
    log.trace(s"Registered MXBean ${objectName.getCanonicalName}")
  }

  protected def unregister(): Unit = {
    unregisterFromMBeanServer(objectName)
    log.trace(s"Unregistered MXBean ${objectName.getCanonicalName}")
  }

  override def onSignal: PartialFunction[Signal, Behavior[T]] = {
    case PostStop =>
      unregister()
      Behaviors.unhandled

    case PreRestart =>
      log.trace(s"Restarting MXBean ${objectName.getCanonicalName}...")
      unregister()
      register()
      Behaviors.unhandled
  }

  protected def doAndSame(op: () => Unit): Behavior[T] = {
    op()
    Behaviors.same
  }

}
