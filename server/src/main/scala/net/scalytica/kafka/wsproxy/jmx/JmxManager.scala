package net.scalytica.kafka.wsproxy.jmx

import org.apache.pekko.NotUsed
import org.apache.pekko.actor.Cancellable
import org.apache.pekko.actor.typed.scaladsl.adapter._
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem}
import org.apache.pekko.http.scaladsl.model.ws.Message
import org.apache.pekko.stream.scaladsl.{Flow, Sink}
import org.apache.pekko.stream.typed.scaladsl.ActorSink
import net.scalytica.kafka.wsproxy.admin.WsKafkaAdminClient
import net.scalytica.kafka.wsproxy.config.Configuration.AppCfg
import net.scalytica.kafka.wsproxy.models.{
  FullConsumerId,
  FullProducerId,
  WsCommit,
  WsConsumerRecord,
  WsProducerId,
  WsProducerInstanceId
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

  def sys: ActorSystem[_]
  def adminClient: WsKafkaAdminClient

  implicit lazy val ec: ExecutionContext = sys.executionContext

  def close(): Unit

}

trait JmxProxyStatusOps { self: BaseJmxManager with WithProxyLogger =>

  protected val proxyStatusActor
      : ActorRef[ProxyStatusProtocol.ProxyStatusCommand] = {
    sys.systemActorOf(
      behavior = ProxyStatusMXBeanActor(appCfg),
      name = "wsproxy-status"
    )
  }

  final protected def updateKafkaClusterInfo(
      ref: ActorRef[ProxyStatusProtocol.ProxyStatusResponse]
  ): Unit = {
    log.trace("Trying to fetch Kafka cluster info...")
    Try(adminClient.clusterInfo) match {
      case Success(brokers) =>
        log.trace("Sending broker info to ProxyStatusMXBeanActor")
        proxyStatusActor.tell(
          ProxyStatusProtocol.UpdateKafkaClusterInfo(brokers, ref)
        )

      case Failure(e) =>
        log.warn(s"Failure when attempting to fetch Kafka broker info.", e)
        log.trace("Sending empty broker info to ProxyStatusMXBeanActor")
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

trait JmxConnectionStatsOps { self: BaseJmxManager with WithProxyLogger =>

  final protected val connectionStatsActor
      : ActorRef[ConnectionsStatsProtocol.ConnectionsStatsCommand] =
    sys.systemActorOf(
      behavior = ConnectionsStatsMXBeanActor(),
      name = "wsproxy-connections"
    )

  def addConsumerConnection(): Unit = try {
    connectionStatsActor.tell(
      ConnectionsStatsProtocol.AddConsumer(sys.ignoreRef)
    )
  } catch {
    case t: Throwable =>
      log.trace("An exception was thrown adding consumer from JMX bean", t)
  }

  def removeConsumerConnection(): Unit = try {
    connectionStatsActor.tell(
      ConnectionsStatsProtocol.RemoveConsumer(sys.ignoreRef)
    )
  } catch {
    case t: Throwable =>
      log.trace("An exception was thrown removing consumer from JMX bean", t)
  }

  def addProducerConnection(): Unit = try {
    connectionStatsActor.tell(
      ConnectionsStatsProtocol.AddProducer(sys.ignoreRef)
    )
  } catch {
    case t: Throwable =>
      log.trace("An exception was thrown adding producer from JMX bean", t)
  }

  def removeProducerConnection(): Unit = try {
    connectionStatsActor.tell(
      ConnectionsStatsProtocol.RemoveProducer(sys.ignoreRef)
    )
  } catch {
    case t: Throwable =>
      log.trace("An exception was thrown removing producer from JMX bean", t)
  }
}

trait JmxConsumerStatsOps { self: BaseJmxManager =>

  final protected def setupConsumerStatsSink(
      ref: ActorRef[ConsumerClientStatsCommand]
  ): Sink[ConsumerClientStatsCommand, NotUsed] =
    ActorSink.actorRef[ConsumerClientStatsCommand](
      ref = ref,
      onCompleteMessage = ConsumerClientStatsProtocol.Stop,
      onFailureMessage = _ => ConsumerClientStatsProtocol.Stop
    )

  def initConsumerClientStatsActor(
      fullConsumerId: FullConsumerId
  ): ActorRef[ConsumerClientStatsCommand] = {
    sys.systemActorOf(
      behavior = ConsumerClientStatsMXBeanActor(fullConsumerId),
      name = consumerStatsName(fullConsumerId)
    )
  }

  final protected val totalConsumerClientStatsActor
      : ActorRef[ConsumerClientStatsCommand] =
    initConsumerClientStatsActor(
      FullConsumerId(WsGroupId("all"), WsClientId("total"))
    )

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

  private[this] def incrementTotalConsumerRecordsSent(): Unit =
    totalConsumerClientStatsActor.tell(
      ConsumerClientStatsProtocol.IncrementRecordSent(sys.ignoreRef)
    )

  private[this] def incrementTotalConsumerCommitsReceived(): Unit =
    totalConsumerClientStatsActor.tell(
      ConsumerClientStatsProtocol.IncrementCommitsReceived(sys.ignoreRef)
    )

}

trait JmxProducerStatsOps { self: BaseJmxManager =>

  final protected val totalProducerClientStatsActor
      : ActorRef[ProducerClientStatsCommand] =
    initProducerClientStatsActor(
      FullProducerId(WsProducerId("all"), Some(WsProducerInstanceId("total")))
    )

  def initProducerClientStatsActor(
      fullProducerId: FullProducerId
  ): ActorRef[ProducerClientStatsCommand] = {
    sys.systemActorOf(
      behavior = ProducerClientStatsMXBeanActor(fullProducerId),
      name = producerStatsName(fullProducerId)
    )
  }

  final protected def setupProducerStatsSink(
      ref: ActorRef[ProducerClientStatsCommand]
  ): Sink[ProducerClientStatsCommand, NotUsed] =
    ActorSink.actorRef[ProducerClientStatsCommand](
      ref = ref,
      onCompleteMessage = ProducerClientStatsProtocol.Stop,
      onFailureMessage = _ => ProducerClientStatsProtocol.Stop
    )

  def producerStatsWireTaps(
      pcsRef: ActorRef[ProducerClientStatsCommand]
  ): (Flow[Message, Message, NotUsed], Flow[Message, Message, NotUsed]) = {
    val in  = producerStatsInboundWireTap(pcsRef)
    val out = producerStatsOutboundWireTap(pcsRef)
    (in, out)
  }

  def producerStatsInboundWireTap(
      pcsRef: ActorRef[ProducerClientStatsCommand]
  ): Flow[Message, Message, NotUsed] = Flow[Message].wireTap {
    val sink = setupProducerStatsSink(pcsRef)
    Flow[Message]
      .map { _ =>
        ProducerClientStatsProtocol.IncrementRecordsReceived(sys.ignoreRef)
      }
      .alsoTo(Sink.foreach(_ => incrementTotalProducerRecordsReceived()))
      .to(sink)
  }

  def producerStatsOutboundWireTap(
      pcsRef: ActorRef[ProducerClientStatsCommand]
  ): Flow[Message, Message, NotUsed] = Flow[Message].wireTap {
    val sink = setupProducerStatsSink(pcsRef)
    Flow[Message]
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

  def close(): Unit = adminClient.close()

}

object JmxManager {

  def apply()(
      implicit appCfg: AppCfg,
      classicSys: org.apache.pekko.actor.ActorSystem
  ): JmxManager = {
    val adminClient = new WsKafkaAdminClient(appCfg)
    new JmxManager(appCfg, classicSys.toTyped, adminClient)
  }

}
