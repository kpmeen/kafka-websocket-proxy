package net.scalytica.kafka.wsproxy.jmx.mbeans

import javax.management.ObjectName

trait WsProxyJmxBean {

  protected def register(): Unit
  protected def unregister(): Unit

  val objectName: ObjectName
}
