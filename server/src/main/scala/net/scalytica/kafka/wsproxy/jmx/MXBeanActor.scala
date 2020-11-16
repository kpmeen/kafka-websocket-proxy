package net.scalytica.kafka.wsproxy.jmx

import akka.actor.typed.scaladsl.{AbstractBehavior, ActorContext, Behaviors}
import akka.actor.typed.{Behavior, PostStop, PreRestart, Signal}
import javax.management.ObjectName
import net.scalytica.kafka.wsproxy.logging.WithProxyLogger

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
    logger.trace(s"Registered MXBean ${objectName.getCanonicalName}")
  }

  protected def unregister(): Unit = {
    unregisterFromMBeanServer(objectName)
    logger.trace(s"Unregistered MXBean ${objectName.getCanonicalName}")
  }

  override def onSignal: PartialFunction[Signal, Behavior[T]] = {
    case PostStop =>
      unregister()
      Behaviors.unhandled

    case PreRestart =>
      logger.trace(s"Restarting MXBean ${objectName.getCanonicalName}...")
      unregister()
      register()
      Behaviors.unhandled
  }

  protected def doAndSame(op: () => Unit): Behavior[T] = {
    op()
    Behaviors.same
  }

}
