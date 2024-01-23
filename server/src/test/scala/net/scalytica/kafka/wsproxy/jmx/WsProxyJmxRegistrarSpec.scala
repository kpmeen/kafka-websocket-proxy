package net.scalytica.kafka.wsproxy.jmx

import net.scalytica.kafka.wsproxy.config.Configuration._
import net.scalytica.kafka.wsproxy.jmx.mbeans.ConnectionsStatsProtocol._
import net.scalytica.kafka.wsproxy.jmx.mbeans.ConsumerClientStatsProtocol._
import net.scalytica.kafka.wsproxy.jmx.mbeans.ProducerClientStatsProtocol._
import net.scalytica.kafka.wsproxy.jmx.mbeans.ProxyStatusProtocol.{
  KafkaClusterInfoUpdated,
  ProxyStatusResponse,
  UpdateKafkaClusterInfo
}
import net.scalytica.kafka.wsproxy.jmx.mbeans.{
  ConnectionsStatsMXBean,
  ConsumerClientStatsMXBean,
  ProducerClientStatsMXBean,
  ProxyStatusMXBean
}
import net.scalytica.kafka.wsproxy.models._
import net.scalytica.test.WsProxyKafkaSpec
import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, TestProbe}
import org.apache.pekko.actor.typed.scaladsl.adapter._
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{BeforeAndAfterAll, OptionValues}

import javax.management.ObjectName
import scala.concurrent.duration._
import scala.reflect.ClassTag

// scalastyle:off magic.number
class WsProxyJmxRegistrarSpec
    extends AnyWordSpec
    with Matchers
    with OptionValues
    with BeforeAndAfterAll
    with WsProxyKafkaSpec {

  val testKit: ActorTestKit = ActorTestKit(system.toTyped)

  val appCfg: AppCfg = plainTestConfig()

  lazy val jmxManager: TestJmxManager = TestJmxManager(appCfg)

  private[this] var testProbes = List.empty[TestProbe[_]]

  val brokerInfoList: List[BrokerInfo] = List(
    BrokerInfo(1, "host1", 1234, None),
    BrokerInfo(2, "host2", 5678, Some("rack2"))
  )

  val objNameQryStr = s"*${jmxManager.beanNamePrefix}*"

  val testObjectNameQuery: ObjectName =
    asObjectName(objNameQryStr, WildcardString)

  def testTypedObjectName[T](implicit ct: ClassTag[T]): ObjectName = {
    val tpe = mxBeanType(ct.runtimeClass)
    asObjectName(objNameQryStr, tpe)
  }

  private[this] def keepTestProbe(probe: TestProbe[_]): Unit = {
    testProbes = testProbes :+ probe
  }

  private[this] def initProxyStatusMBean(): Unit = {
    val probe = testKit.createTestProbe[ProxyStatusResponse]()
    keepTestProbe(probe)
    jmxManager.proxyStatusActorRef ! UpdateKafkaClusterInfo(
      brokerInfoList,
      probe.ref
    )
    probe.expectMessage(5 seconds, KafkaClusterInfoUpdated)
  }

  private[this] def initConnectionStatsMBean(): Unit = {
    val probe = testKit.createTestProbe[ConnectionStatsResponse]()
    keepTestProbe(probe)
    jmxManager.connectionStatsActorRef ! AddProducer(probe.ref)
    probe.expectMessage(5 seconds, ProducerAdded)
    jmxManager.connectionStatsActorRef ! AddConsumer(probe.ref)
    probe.expectMessage(5 seconds, ConsumerAdded)
  }

  private[this] def initTotalConsumerStatsMBean(): Unit = {
    val probe = testKit.createTestProbe[ConsumerClientStatsResponse]()
    keepTestProbe(probe)
    jmxManager.totalConsumerStatsRef ! IncrementRecordSent(probe.ref)
    probe.expectMessage(5 seconds, RecordsSentIncremented)
    jmxManager.totalConsumerStatsRef ! IncrementCommitsReceived(probe.ref)
    probe.expectMessage(5 seconds, CommitsReceivedIncremented)
  }

  private[this] def initTotalProducerStatsMBean(): Unit = {
    val probe = testKit.createTestProbe[ProducerClientStatsResponse]()
    keepTestProbe(probe)
    jmxManager.totalProducerStatsRef ! IncrementRecordsReceived(probe.ref)
    probe.expectMessage(5 seconds, RecordsReceivedIncremented)
    jmxManager.totalProducerStatsRef ! IncrementAcksSent(probe.ref)
    probe.expectMessage(5 seconds, AcksSentIncremented)
  }

  private[this] def initConsumerStatsMBean(clientId: String): Unit = {
    val probe = testKit.createTestProbe[ConsumerClientStatsResponse]()
    keepTestProbe(probe)
    val ref = jmxManager.initConsumerClientStatsActor(
      FullConsumerId(
        WsGroupId(s"${jmxManager.beanNamePrefix}-$clientId"),
        WsClientId(clientId)
      )
    )
    ref ! IncrementRecordSent(probe.ref)
    probe.expectMessage(5 seconds, RecordsSentIncremented)
    ref ! IncrementCommitsReceived(probe.ref)
    probe.expectMessage(5 seconds, CommitsReceivedIncremented)
  }

  private[this] def initProducerStatsMBean(clientId: String): Unit = {
    val probe = testKit.createTestProbe[ProducerClientStatsResponse]()
    keepTestProbe(probe)
    val ref = jmxManager.initProducerClientStatsActor(
      FullProducerId(
        WsProducerId(s"${jmxManager.beanNamePrefix}-$clientId"),
        None
      )
    )
    ref ! IncrementRecordsReceived(probe.ref)
    probe.expectMessage(5 seconds, RecordsReceivedIncremented)
    ref ! IncrementAcksSent(probe.ref)
    probe.expectMessage(5 seconds, AcksSentIncremented)
  }

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    WsProxyJmxRegistrar.unregisterNamedWsProxyMBeans(testObjectNameQuery)
    initProxyStatusMBean()
    initConnectionStatsMBean()
    initTotalConsumerStatsMBean()
    initTotalProducerStatsMBean()
    initConsumerStatsMBean("consumer-1")
    initConsumerStatsMBean("consumer-2")
    initProducerStatsMBean("producer-1")
    initProducerStatsMBean("producer-2")
  }

  override protected def afterAll(): Unit = {
    super.afterAll()
    testProbes.foreach(_.stop())
    testKit.shutdownTestKit()
  }

  "The WsProxyJmxRegistrar" should {

    "list all registered MBeans for the proxy domain" in {
      val names = WsProxyJmxRegistrar.allWSProxyMBeanNames
      names.size must be >= 8
    }

    "list MBeans registered in this test for the proxy domain" in {
      WsProxyJmxRegistrar.allNamesFor(testObjectNameQuery) must have size 8
    }

    "list all MBeans for a given type in the proxy domain" in {
      WsProxyJmxRegistrar.namesForNameAndType[ConnectionsStatsMXBean](
        objNameQryStr
      ) must have size 1

      WsProxyJmxRegistrar.namesForNameAndType[ProxyStatusMXBean](
        objNameQryStr
      ) must have size 1

      WsProxyJmxRegistrar.namesForNameAndType[ConsumerClientStatsMXBean](
        objNameQryStr
      ) must have size 3

      WsProxyJmxRegistrar.namesForNameAndType[ProducerClientStatsMXBean](
        objNameQryStr
      ) must have size 3
    }

    "unregister MBeans for the proxy domain" in {
      WsProxyJmxRegistrar.allNamesFor(testObjectNameQuery) must have size 8

      WsProxyJmxRegistrar.unregisterNamedWsProxyMBeans(testObjectNameQuery)

      WsProxyJmxRegistrar.allNamesFor(testObjectNameQuery) mustBe empty
    }

  }

}
