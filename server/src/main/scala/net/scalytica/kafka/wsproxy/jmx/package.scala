package net.scalytica.kafka.wsproxy

import javax.management.ObjectName
import net.scalytica.kafka.wsproxy.models.{WsClientId, WsGroupId}

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

  def producerStatsName(clientId: WsClientId): String =
    s"wsproxy-producer-stats-${clientId.value}"

  def consumerStatsName(clientId: WsClientId, groupId: WsGroupId): String =
    s"wsproxy-consumer-stats-${groupId.value}-${clientId.value}"
}
