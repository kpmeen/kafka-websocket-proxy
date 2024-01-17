package net.scalytica.kafka.wsproxy.jmx

import net.scalytica.kafka.wsproxy.admin.WsKafkaAdminClient
import net.scalytica.kafka.wsproxy.config.Configuration._
import net.scalytica.kafka.wsproxy.jmx.mbeans.ConnectionsStatsProtocol.{
  AddConsumer,
  AddProducer,
  ConnectionStatsResponse,
  ConsumerAdded,
  ProducerAdded
}
import net.scalytica.kafka.wsproxy.jmx.mbeans.ConsumerClientStatsProtocol.{
  CommitsReceivedIncremented,
  ConsumerClientStatsCommand,
  ConsumerClientStatsResponse,
  IncrementCommitsReceived,
  IncrementRecordSent,
  RecordsSentIncremented
}
import net.scalytica.kafka.wsproxy.jmx.mbeans.ProducerClientStatsProtocol.{
  AcksSentIncremented,
  IncrementAcksSent,
  IncrementRecordsReceived,
  ProducerClientStatsCommand,
  ProducerClientStatsResponse,
  RecordsReceivedIncremented
}
import net.scalytica.test.WsProxyKafkaSpec
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.scaladsl.adapter._
import net.scalytica.kafka.wsproxy.jmx.mbeans.{
  ConnectionsStatsMXBean,
  ConnectionsStatsProtocol,
  ConsumerClientStatsMXBean,
  ProducerClientStatsMXBean,
  ProxyStatusMXBean,
  ProxyStatusProtocol
}
import net.scalytica.kafka.wsproxy.jmx.mbeans.ProxyStatusProtocol.{
  KafkaClusterInfoUpdated,
  ProxyStatusResponse,
  UpdateKafkaClusterInfo
}
import net.scalytica.kafka.wsproxy.logging.WithProxyLogger
import net.scalytica.kafka.wsproxy.models.BrokerInfo
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem}
import org.scalatest.{BeforeAndAfterAll, OptionValues}
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.duration._

class WsProxyJmxRegistrarSpec
    extends AnyWordSpec
    with Matchers
    with OptionValues
    with BeforeAndAfterAll
    with WsProxyKafkaSpec {

  val testKit: ActorTestKit = ActorTestKit(system.toTyped)

  val appCfg: AppCfg = plainTestConfig()

  lazy val jmxManager: TestJmxManager = TestJmxManager(appCfg)

  val brokerInfoList: List[BrokerInfo] = List(
    BrokerInfo(1, "host1", 1234, None),
    BrokerInfo(2, "host2", 5678, Some("rack2"))
  )

  private[this] def initProxyStatusMBean(): Unit = {
    val probe = testKit.createTestProbe[ProxyStatusResponse]()
    jmxManager.proxyStatusActorRef ! UpdateKafkaClusterInfo(
      brokerInfoList,
      probe.ref
    )
    probe.expectMessage(5 seconds, KafkaClusterInfoUpdated)
  }

  private[this] def initConnectionStatsMBean(): Unit = {
    val probe = testKit.createTestProbe[ConnectionStatsResponse]()
    jmxManager.connectionStatsActorRef ! AddProducer(probe.ref)
    probe.expectMessage(5 seconds, ProducerAdded)
    jmxManager.connectionStatsActorRef ! AddConsumer(probe.ref)
    probe.expectMessage(5 seconds, ConsumerAdded)
  }

  private[this] def initTotalConsumerStatsMBean(): Unit = {
    val probe = testKit.createTestProbe[ConsumerClientStatsResponse]()
    jmxManager.totalConsumerStatsRef ! IncrementRecordSent(probe.ref)
    probe.expectMessage(5 seconds, RecordsSentIncremented)
    jmxManager.totalConsumerStatsRef ! IncrementCommitsReceived(probe.ref)
    probe.expectMessage(5 seconds, CommitsReceivedIncremented)
  }

  private[this] def initTotalProducerStatsMBean(): Unit = {
    val probe = testKit.createTestProbe[ProducerClientStatsResponse]()
    jmxManager.totalProducerStatsRef ! IncrementRecordsReceived(probe.ref)
    probe.expectMessage(5 seconds, RecordsReceivedIncremented)
    jmxManager.totalProducerStatsRef ! IncrementAcksSent(probe.ref)
    probe.expectMessage(5 seconds, AcksSentIncremented)
  }

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    initProxyStatusMBean()
    initConnectionStatsMBean()
    initTotalConsumerStatsMBean()
    initTotalProducerStatsMBean()
  }

  override protected def afterAll(): Unit = {
    super.afterAll()
    testKit.shutdownTestKit()
  }

  "The WsProxyJmxRegistrar" should {

    "list all the MBeans for the kafka websocket proxy domain" in {
      WsProxyJmxRegistrar.listAllWSProxyMBeanNames must have size 4
    }

    "list all MBeans for a given type" in {
      WsProxyJmxRegistrar
        .listAllMBeanNamesForType[ConnectionsStatsMXBean] must have size 1

      WsProxyJmxRegistrar
        .listAllMBeanNamesForType[ProxyStatusMXBean] must have size 1

      WsProxyJmxRegistrar
        .listAllMBeanNamesForType[ConsumerClientStatsMXBean] must have size 1

      WsProxyJmxRegistrar
        .listAllMBeanNamesForType[ProducerClientStatsMXBean] must have size 1
    }

    "unregister all MBeans for the kafka websocket proxy domain" in {
      WsProxyJmxRegistrar.listAllWSProxyMBeanNames must have size 4

      WsProxyJmxRegistrar.unregisterAllWsProxyMBeans()

      WsProxyJmxRegistrar.listAllWSProxyMBeanNames mustBe empty
    }

  }

  case class TestJmxManager(appCfg: AppCfg)
      extends BaseJmxManager
      with JmxProxyStatusOps
      with JmxConnectionStatsOps
      with JmxConsumerStatsOps
      with JmxProducerStatsOps
      with WithProxyLogger {

    override def adminClient: WsKafkaAdminClient = {
      log.info("The admin client is not available for this test suite.")
      throw new NotImplementedError("Not used in tests")
    }

    override def sys: ActorSystem[_] = system.toTyped

    override def close(): Unit = {}

    def proxyStatusActorRef: ActorRef[ProxyStatusProtocol.ProxyStatusCommand] =
      proxyStatusActor

    def connectionStatsActorRef
        : ActorRef[ConnectionsStatsProtocol.ConnectionsStatsCommand] =
      connectionStatsActor

    def totalConsumerStatsRef: ActorRef[ConsumerClientStatsCommand] =
      totalConsumerClientStatsActor

    def totalProducerStatsRef: ActorRef[ProducerClientStatsCommand] =
      totalProducerClientStatsActor
  }

}
