package net.scalytica.kafka.wsproxy.jmx

import java.lang.management.ManagementFactory

import akka.actor.typed.Behavior
import javax.management._
import net.scalytica.kafka.wsproxy.jmx.mbeans.ConsumerClientStatsMXBean
import net.scalytica.kafka.wsproxy.logging.WithProxyLogger
import net.scalytica.kafka.wsproxy.models.{WsClientId, WsGroupId}

import scala.reflect.ClassTag
import scala.util.Try

trait WsProxyJmxQueries extends WithProxyLogger {
  protected lazy val mbs: MBeanServer = ManagementFactory.getPlatformMBeanServer

  def findMBean(on: ObjectName): Option[MBeanInfo] = {
    val tryRes = Try(mbs.getMBeanInfo(on))
    tryRes.recoverWith {
      case t: InstanceNotFoundException =>
        logger.debug(s"MBean with ObjectName ${on.toString} could not be found")
        throw t

      case t: Throwable =>
        logger.info(s"Error querying MBean with ObjectName ${on.toString}", t)
        throw t
    }
    tryRes.toOption
  }

  def findMBeanByType[T](beanName: String)(
      implicit ct: ClassTag[T]
  ): Option[MBeanInfo] = {
    val on = asObjectName(beanName, mxBeanType(ct.runtimeClass))
    findMBean(on)
  }

  def findConsumerClientMBean(
      clientId: WsClientId,
      groupId: WsGroupId
  ): Option[MBeanInfo] = {
    findMBeanByType[ConsumerClientStatsMXBean](
      consumerStatsName(clientId, groupId)
    )
  }

  def queryMBeanAttributeByTypes[T](name: String, attribute: String)(
      implicit beanCt: ClassTag[T]
  ): Option[AnyRef] = {
    val on = asObjectName(name, mxBeanType(beanCt.runtimeClass))
    queryMBeanAttribute(on, attribute)
  }

  def queryMBeanAttribute(
      on: ObjectName,
      attribute: String
  ): Option[AnyRef] = {
    val tryRes = Try(mbs.getAttribute(on, attribute))
    tryRes.recoverWith {
      case t: InstanceNotFoundException =>
        logger.debug(s"MBean with ObjectName ${on.toString} could not be found")
        throw t

      case t: AttributeNotFoundException =>
        logger.debug(
          s"Attribute $attribute was not found on " +
            s"MBean with ObjectName ${on.toString}"
        )
        throw t

      case t: Throwable =>
        logger.trace(
          s"Error querying MBean with ObjectName ${on.toString} " +
            s"and attribute ${attribute.mkString(", ")}",
          t
        )
        throw t
    }
    tryRes.toOption
  }
}

object WsProxyJmxRegistrar extends WsProxyJmxQueries {

  @throws[InstanceAlreadyExistsException]
  @throws[MBeanRegistrationException]
  @throws[RuntimeMBeanException]
  @throws[RuntimeErrorException]
  @throws[NotCompliantMBeanException]
  @throws[RuntimeOperationsException]
  def registerToMBeanServer[T](
      actor: Behavior[T],
      objName: ObjectName
  ): ObjectInstance = {
    logger.debug(s"Registering MBean ${objName.getCanonicalName}")
    mbs.registerMBean(actor, objName)
  }

  @throws[RuntimeOperationsException]
  @throws[RuntimeMBeanException]
  @throws[RuntimeErrorException]
  @throws[InstanceNotFoundException]
  @throws[MBeanRegistrationException]
  def unregisterFromMBeanServer(objName: ObjectName): Unit = {
    logger.debug(s"Unregistering MBean ${objName.getCanonicalName}")
    mbs.unregisterMBean(objName)
  }

}
