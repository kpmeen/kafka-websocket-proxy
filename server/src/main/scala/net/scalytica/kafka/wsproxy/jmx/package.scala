package net.scalytica.kafka.wsproxy

import javax.management.ObjectName
import net.scalytica.kafka.wsproxy.models.{FullConsumerId, FullProducerId}

package object jmx {

  private[jmx] val BasePackage =
    this.getClass.getPackageName.stripSuffix(".jmx")

  private[jmx] val MXBeanSuffix = "MXBean"

  def asObjectName(name: String, tpe: String): ObjectName = new ObjectName(
    BasePackage, {
      import scala.jdk.CollectionConverters._
      new java.util.Hashtable(
        Map(
          "name" -> name,
          "type" -> tpe
        ).asJava
      )
    }
  )

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
