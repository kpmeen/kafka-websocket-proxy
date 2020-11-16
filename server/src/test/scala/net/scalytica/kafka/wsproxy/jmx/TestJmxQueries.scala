package net.scalytica.kafka.wsproxy.jmx

import javax.management.JMX

class TestJmxQueries extends WsProxyJmxQueries {

  def getMxBean[B](beanName: String, clazz: Class[B]): B = {
    val on = asObjectName(beanName, mxBeanType(clazz))
    JMX.newMXBeanProxy(mbs, on, clazz)
  }

}
