package net.scalytica.kafka.wsproxy.jmx

import java.util.UUID

import akka.NotUsed
import akka.actor.Cancellable
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.{ActorRef, ActorSystem}
import akka.stream.scaladsl.{Flow, Sink}
import akka.stream.typed.scaladsl.ActorSink
import net.scalytica.kafka.wsproxy.admin.WsKafkaAdminClient
import net.scalytica.kafka.wsproxy.config.Configuration.AppCfg
import net.scalytica.kafka.wsproxy.models.{
  WsCommit,
  WsConsumerRecord,
  WsProducerRecord,
  WsProducerResult
}

import scala.concurrent.ExecutionContext
// scalastyle:off
import net.scalytica.kafka.wsproxy.jmx.mbeans.ConsumerClientStatsProtocol.ConsumerClientStatsCommand
import net.scalytica.kafka.wsproxy.jmx.mbeans.ProducerClientStatsProtocol.ProducerClientStatsCommand
// scalastyle:on
import net.scalytica.kafka.wsproxy.jmx.mbeans._
import net.scalytica.kafka.wsproxy.logging.WithProxyLogger
import net.scalytica.kafka.wsproxy.models.{WsClientId, WsGroupId}

import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

trait BaseJmxManager {
  val appCfg: AppCfg
  val sys: ActorSystem[_]
  val adminClient: WsKafkaAdminClient

  implicit lazy val ec: ExecutionContext = sys.executionContext

}

trait JmxProxyStatusOps { self: BaseJmxManager with WithProxyLogger =>

  protected val proxyStatusActor = {
    sys.systemActorOf(
      behavior = ProxyStatusMXBeanActor(appCfg),
      name = "wsproxy-status"
    )
  }

  final protected def updateKafkaClusterInfo(
      ref: ActorRef[ProxyStatusProtocol.ProxyStatusResponse]
  ): Unit = {
    logger.trace("Trying to fetch Kafka cluster info...")
    Try(adminClient.clusterInfo) match {
      case Success(brokers) =>
        logger.trace("Sending broker info to ProxyStatusMXBeanActor")
        proxyStatusActor.tell(
          ProxyStatusProtocol.UpdateKafkaClusterInfo(brokers, ref)
        )

      case Failure(e) =>
        logger.warn(s"Failure when attempting to fetch Kafka broker info.", e)
        logger.trace("Sending empty broker info to ProxyStatusMXBeanActor")
        proxyStatusActor.tell(ProxyStatusProtocol.ClearBrokers(ref))
    }
  }

  final protected def scheduleProxyStatus(): Cancellable = {
    val interval = appCfg.server.jmx.manager.proxyStatusInterval
    sys.scheduler.scheduleAtFixedRate(0 seconds, interval) { () =>
      updateKafkaClusterInfo(sys.ignoreRef)
    }
  }
}

trait JmxConnectionStatsOps { self: BaseJmxManager =>

  final protected val connectionStatsActor = sys.systemActorOf(
    behavior = ConnectionsStatsMXBeanActor(),
    name = "wsproxy-connections"
  )

  def addConsumerConnection(): Unit = {
    connectionStatsActor.tell(
      ConnectionsStatsProtocol.AddConsumer(sys.ignoreRef)
    )
  }

  def removeConsumerConnection(): Unit = {
    connectionStatsActor.tell(
      ConnectionsStatsProtocol.RemoveConsumer(sys.ignoreRef)
    )
  }

  def addProducerConnection(): Unit = {
    connectionStatsActor.tell(
      ConnectionsStatsProtocol.AddProducer(sys.ignoreRef)
    )
  }

  def removeProducerConnection(): Unit = {
    connectionStatsActor.tell(
      ConnectionsStatsProtocol.RemoveProducer(sys.ignoreRef)
    )
  }
}

trait JmxConsumerStatsOps { self: BaseJmxManager =>

  final protected def setupConsumerStatsSink(
      ref: ActorRef[ConsumerClientStatsCommand]
  ) = ActorSink.actorRef[ConsumerClientStatsCommand](
    ref = ref,
    onCompleteMessage = ConsumerClientStatsProtocol.Stop,
    onFailureMessage = _ => ConsumerClientStatsProtocol.Stop
  )

  def initConsumerClientStatsActor(
      clientId: WsClientId,
      groupId: WsGroupId
  ): ActorRef[ConsumerClientStatsCommand] = {
    sys.systemActorOf(
      behavior = ConsumerClientStatsMXBeanActor(clientId, groupId),
      name = consumerStatsName(clientId, groupId)
    )
  }

  final protected val totalConsumerClientStatsActor =
    initConsumerClientStatsActor(WsClientId("total"), WsGroupId("all"))

  def consumerStatsInboundWireTap(
      ccsRef: ActorRef[ConsumerClientStatsCommand]
  ): Flow[WsCommit, WsCommit, NotUsed] =
    Flow[WsCommit].wireTap {
      val sink = setupConsumerStatsSink(ccsRef)
      Flow[WsCommit]
        .map(_ =>
          ConsumerClientStatsProtocol.IncrementCommitsReceived(sys.ignoreRef)
        )
        .alsoTo(Sink.foreach(_ => incrementTotalConsumerCommitsReceived()))
        .to(sink)
    }

  def consumerStatsOutboundWireTap[K, V](
      ccsRef: ActorRef[ConsumerClientStatsCommand]
  ): Flow[WsConsumerRecord[K, V], WsConsumerRecord[K, V], NotUsed] =
    Flow[WsConsumerRecord[K, V]].wireTap {
      val sink = setupConsumerStatsSink(ccsRef)
      Flow[WsConsumerRecord[K, V]]
        .map { _ =>
          ConsumerClientStatsProtocol.IncrementRecordSent(sys.ignoreRef)
        }
        .alsoTo(Sink.foreach(_ => incrementTotalConsumerRecordsSent()))
        .to(sink)
    }

  def incrementTotalConsumerRecordsSent(): Unit =
    totalConsumerClientStatsActor.tell(
      ConsumerClientStatsProtocol.IncrementRecordSent(sys.ignoreRef)
    )

  def incrementTotalConsumerCommitsReceived(): Unit =
    totalConsumerClientStatsActor.tell(
      ConsumerClientStatsProtocol.IncrementCommitsReceived(sys.ignoreRef)
    )

}

trait JmxProducerStatsOps { self: BaseJmxManager =>

  final protected val totalProducerClientStatsActor =
    initProducerClientStatsActor(WsClientId("all"))

  def initProducerClientStatsActor(
      clientId: WsClientId
  ): ActorRef[ProducerClientStatsCommand] = {
    sys.systemActorOf(
      behavior = ProducerClientStatsMXBeanActor(clientId),
      name = producerStatsName(clientId)
    )
  }

  def initProducerClientStatsActorForConnection(
      clientId: WsClientId
  ): ActorRef[ProducerClientStatsCommand] = {
    val suffix = UUID.randomUUID()
    val cid    = WsClientId(s"${clientId.value}-${suffix.toString}")
    initProducerClientStatsActor(cid)
  }

  final protected def setupProducerStatsSink(
      ref: ActorRef[ProducerClientStatsCommand]
  ): Sink[ProducerClientStatsCommand, NotUsed] =
    ActorSink.actorRef[ProducerClientStatsCommand](
      ref = ref,
      onCompleteMessage = ProducerClientStatsProtocol.Stop,
      onFailureMessage = _ => ProducerClientStatsProtocol.Stop
    )

  type ProducerInWireTapFlow[K, V] =
    Flow[WsProducerRecord[K, V], WsProducerRecord[K, V], NotUsed]

  type ProducerOutWireTapFlow =
    Flow[WsProducerResult, WsProducerResult, NotUsed]

  def producerStatsWireTaps[K, V](
      pcsRef: ActorRef[ProducerClientStatsCommand]
  ): (ProducerInWireTapFlow[K, V], ProducerOutWireTapFlow) = {
    val sink = setupProducerStatsSink(pcsRef)
    val in   = producerStatsInboundWireTap[K, V](sink)
    val out  = producerStatsOutboundWireTap(sink)

    (in, out)
  }

  def producerStatsInboundWireTap[K, V](
      sink: Sink[ProducerClientStatsCommand, NotUsed]
  ): ProducerInWireTapFlow[K, V] = Flow[WsProducerRecord[K, V]].wireTap {
    Flow[WsProducerRecord[K, V]]
      .map { _ =>
        ProducerClientStatsProtocol.IncrementRecordsReceived(sys.ignoreRef)
      }
      .alsoTo(Sink.foreach(_ => incrementTotalProducerRecordsReceived()))
      .to(sink)
  }

  def producerStatsOutboundWireTap(
      sink: Sink[ProducerClientStatsCommand, NotUsed]
  ): ProducerOutWireTapFlow = Flow[WsProducerResult].wireTap {
    Flow[WsProducerResult]
      .map { _ =>
        ProducerClientStatsProtocol.IncrementAcksSent(sys.ignoreRef)
      }
      .alsoTo(Sink.foreach(_ => incrementTotalProducerAcksSent()))
      .to(sink)
  }

  def incrementTotalProducerRecordsReceived(): Unit =
    totalProducerClientStatsActor.tell(
      ProducerClientStatsProtocol.IncrementRecordsReceived(sys.ignoreRef)
    )

  def incrementTotalProducerAcksSent(): Unit =
    totalProducerClientStatsActor.tell(
      ProducerClientStatsProtocol.IncrementAcksSent(sys.ignoreRef)
    )
}

case class JmxManager(
    appCfg: AppCfg,
    sys: ActorSystem[_],
    adminClient: WsKafkaAdminClient
) extends BaseJmxManager
    with JmxProxyStatusOps
    with JmxConnectionStatsOps
    with JmxConsumerStatsOps
    with JmxProducerStatsOps
    with WithProxyLogger {

  { val _ = scheduleProxyStatus() }

}

object JmxManager {

  def apply()(
      implicit appCfg: AppCfg,
      classicSys: akka.actor.ActorSystem
  ): JmxManager = {
    val adminClient = new WsKafkaAdminClient(appCfg)
    new JmxManager(appCfg, classicSys.toTyped, adminClient)
  }

}
