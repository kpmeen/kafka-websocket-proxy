package net.scalytica.kafka.wsproxy.jmx

import java.lang.management.ManagementFactory
import org.apache.pekko.actor.typed.Behavior

import javax.management._
import net.scalytica.kafka.wsproxy.jmx.mbeans.{
  ConsumerClientStatsMXBean,
  ProducerClientStatsMXBean
}
import net.scalytica.kafka.wsproxy.logging.WithProxyLogger
import net.scalytica.kafka.wsproxy.models.{FullConsumerId, FullProducerId}

import scala.jdk.CollectionConverters._
import scala.reflect.ClassTag
import scala.util.Try

trait WsProxyJmxQueries extends WithProxyLogger {
  protected lazy val mbs: MBeanServer = ManagementFactory.getPlatformMBeanServer

  def findByName(on: ObjectName): Option[MBeanInfo] = {
    val tryRes = Try(mbs.getMBeanInfo(on))
    tryRes.recoverWith {
      case t: InstanceNotFoundException =>
        log.debug(s"MBean with ObjectName ${on.toString} could not be found")
        throw t

      case t: Throwable =>
        log.info(s"Error querying MBean with ObjectName ${on.toString}", t)
        throw t
    }
    tryRes.toOption
  }

  def allNamesFor(on: ObjectName): Set[ObjectName] = {
    log.trace(s"Using ObjectName ${on.getCanonicalName}")
    val res: Set[ObjectName] =
      Try(mbs.queryNames(on, null)) // scalastyle:ignore
        .recoverWith { case t: Throwable =>
          log.info(s"Error trying to fetch name of MBeans", t)
          throw t
        }
        .toOption
        .map(_.asScala)
        .map(m => Set.from(m))
        .getOrElse(Set.empty)

    log.trace(s"Found the following MBeans:\n${res.mkString("\n")}")

    res
  }

  def allWSProxyMBeanNames: Set[ObjectName] = {
    allNamesFor(AllDomainMXBeansQuery)
  }

  def allNamesForType[T](
      implicit ct: ClassTag[T]
  ): Set[ObjectName] = {
    val tpe = mxBeanType(ct.runtimeClass)
    allNamesFor(asObjectName(WildcardString, tpe))
  }

  def namesForNameAndType[T](beanName: String)(
      implicit ct: ClassTag[T]
  ): Set[ObjectName] = {
    val tpe = mxBeanType(ct.runtimeClass)
    allNamesFor(asObjectName(beanName, tpe))
  }

  def findByTypeAndName[T](objectNameString: String)(
      implicit ct: ClassTag[T]
  ): Option[MBeanInfo] = {
    val on = asObjectName(objectNameString, mxBeanType(ct.runtimeClass))
    findByName(on)
  }

  def findConsumerClientMBean(
      fullConsumerId: FullConsumerId
  ): Option[MBeanInfo] = {
    findByTypeAndName[ConsumerClientStatsMXBean](
      consumerStatsName(fullConsumerId)
    )
  }

  def findProducerClientMBean(
      fullProducerId: FullProducerId
  ): Option[MBeanInfo] = {
    findByTypeAndName[ProducerClientStatsMXBean](
      producerStatsName(fullProducerId)
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
        log.debug(s"MBean with ObjectName ${on.toString} could not be found")
        throw t

      case t: AttributeNotFoundException =>
        log.debug(
          s"Attribute $attribute was not found on " +
            s"MBean with ObjectName ${on.toString}"
        )
        throw t

      case t: Throwable =>
        log.trace(
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
    log.debug(s"Registering MBean ${objName.getCanonicalName}")
    mbs.registerMBean(actor, objName)
  }

  @throws[RuntimeOperationsException]
  @throws[RuntimeMBeanException]
  @throws[RuntimeErrorException]
  def unregisterFromMBeanServer(objName: ObjectName): Unit = {
    log.debug(s"Unregistering MBean ${objName.getCanonicalName}")
    mbs.unregisterMBean(objName)
  }

  @throws[RuntimeOperationsException]
  @throws[RuntimeMBeanException]
  @throws[RuntimeErrorException]
  def unregisterAllWsProxyMBeans(): Unit = {
    log.debug(s"Unregistering all MBeans for Kafka WebSocket Proxy")
    allWSProxyMBeanNames.foreach(unregisterFromMBeanServer)
  }

  @throws[RuntimeOperationsException]
  @throws[RuntimeMBeanException]
  @throws[RuntimeErrorException]
  def unregisterNamedWsProxyMBeans(objName: ObjectName): Unit = {
    log.debug(
      "Unregistering MBeans matching the following ObjectName:" +
        s" ${objName.getCanonicalName}"
    )
    allNamesFor(objName).foreach(on => unregisterFromMBeanServer(on))
  }

}
