package net.scalytica.kafka.wsproxy.jmx

import net.scalytica.kafka.wsproxy.logging.WithProxyLogger

import javax.management.JMX

class TestJmxQueries extends WsProxyJmxQueries with WithProxyLogger {

  def getMxBean[B](beanName: String, clazz: Class[B]): B = {
    val on = asObjectName(beanName, mxBeanType(clazz))
    JMX.newMXBeanProxy(mbs, on, clazz)
  }

  def removeMxBean[B](beanName: String, clazz: Class[B]): Unit = {
    val on = asObjectName(beanName, mxBeanType(clazz))
    try {
      WsProxyJmxRegistrar.unregisterFromMBeanServer(on)
    } catch {
      case t: Throwable => log.info("Nothing to unregister", t)
    }
  }

}
