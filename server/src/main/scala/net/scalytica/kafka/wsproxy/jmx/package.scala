package net.scalytica.kafka.wsproxy

import javax.management.ObjectName
import net.scalytica.kafka.wsproxy.models.{FullConsumerId, FullProducerId}

package object jmx {

  private[jmx] val BasePackage =
    this.getClass.getPackageName.stripSuffix(".jmx")

  private[jmx] val MXBeanSuffix = "MXBean"

  private[this] def asObjectNameInternal(
      name: String,
      tpe: String = WildcardString
  ): ObjectName = {
    val attributes: Map[String, String] = Map("name" -> name, "type" -> tpe)
    new ObjectName(
      BasePackage, {
        import scala.jdk.CollectionConverters._
        new java.util.Hashtable(attributes.asJava)
      }
    )
  }

  def asObjectName(name: String, tpe: String): ObjectName =
    asObjectNameInternal(name, tpe)

  lazy val AllDomainMXBeansQuery: ObjectName =
    asObjectNameInternal(WildcardString)

  lazy val WildcardString: String = "*"

  def mxBeanType[T](clazz: Class[T]): String = {
    clazz.getInterfaces
      .find(_.getSimpleName.endsWith(MXBeanSuffix))
      .map(_.getSimpleName)
      .getOrElse(clazz.getSimpleName)
  }

  def producerStatsName(fullProducerId: FullProducerId): String =
    s"wsproxy-producer-stats-${fullProducerId.value}"

  def consumerStatsName(fullConsumerId: FullConsumerId): String =
    s"wsproxy-consumer-stats-${fullConsumerId.value}"
}
