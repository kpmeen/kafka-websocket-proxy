package net.scalytica.kafka.wsproxy.jmx.mbeans

import net.scalytica.kafka.wsproxy.jmx.TestJmxQueries
import net.scalytica.test.WsProxySpec
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.scaladsl.adapter._
import org.scalatest.{BeforeAndAfterAll, OptionValues}
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

trait MXBeanSpecLike
    extends AnyWordSpecLike
    with Matchers
    with OptionValues
    with BeforeAndAfterAll
    with WsProxySpec {

  val tk: ActorTestKit     = ActorTestKit(system.toTyped)
  val jmxq: TestJmxQueries = new TestJmxQueries

  override protected def beforeAll(): Unit = super.beforeAll()

  override protected def afterAll(): Unit = {
    tk.shutdownTestKit()
    super.afterAll()
  }

}
