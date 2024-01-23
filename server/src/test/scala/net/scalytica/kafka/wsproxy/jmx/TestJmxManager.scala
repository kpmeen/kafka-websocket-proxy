package net.scalytica.kafka.wsproxy.jmx

import net.scalytica.kafka.wsproxy.admin.WsKafkaAdminClient
import net.scalytica.kafka.wsproxy.config.Configuration.AppCfg
import net.scalytica.kafka.wsproxy.jmx.mbeans.ConsumerClientStatsProtocol._
import net.scalytica.kafka.wsproxy.jmx.mbeans.ProducerClientStatsProtocol._
import net.scalytica.kafka.wsproxy.jmx.mbeans.{
  ConnectionsStatsProtocol,
  ProxyStatusProtocol
}
import net.scalytica.kafka.wsproxy.logging.WithProxyLogger
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem}
import org.apache.pekko.actor.typed.scaladsl.adapter._
import org.apache.pekko.actor.{ActorSystem => ClassicActorSystem}

case class TestJmxManager(appCfg: AppCfg)(implicit system: ClassicActorSystem)
    extends BaseJmxManager
    with JmxProxyStatusOps
    with JmxConnectionStatsOps
    with JmxConsumerStatsOps
    with JmxProducerStatsOps
    with WithProxyLogger {

  val beanNamePrefix: String = "test-suite"

  override protected val connectionStatsActorName: String =
    s"$beanNamePrefix-wsproxy-connections"

  override protected val proxyStatusActorName: String =
    s"$beanNamePrefix-wsproxy-status"

  override protected val totalConsumerStatsPrefix: String =
    s"$beanNamePrefix-all"
  override protected val totalProducerStatsPrefix: String =
    s"$beanNamePrefix-all"

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
