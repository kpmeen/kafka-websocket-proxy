package net.scalytica.kafka.wsproxy.jmx.mbeans

import java.beans.ConstructorProperties
import java.time.{LocalDateTime, ZoneOffset}

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import net.scalytica.kafka.wsproxy.config.Configuration.AppCfg
import net.scalytica.kafka.wsproxy.jmx.MXBeanActor
import net.scalytica.kafka.wsproxy.jmx.mbeans.ProxyStatusProtocol._
import net.scalytica.kafka.wsproxy.models.BrokerInfo

import scala.beans.BeanProperty

case class BrokerInfoMXView @ConstructorProperties(
  Array("id", "host", "port", "rack")
) private (
    @BeanProperty id: Int,
    @BeanProperty host: String,
    @BeanProperty port: Int,
    @BeanProperty rack: String
)

object BrokerInfoMXView {

  def apply(bi: BrokerInfo): BrokerInfoMXView = BrokerInfoMXView(
    id = bi.id,
    host = bi.host,
    port = bi.port,
    rack = bi.rack.orNull
  )
}

case class BrokerInfoListMXView @ConstructorProperties(Array("brokers")) (
    @BeanProperty brokers: Array[BrokerInfoMXView]
)

object BrokerInfoListMXView {

  def apply(brokers: List[BrokerInfo]): BrokerInfoListMXView = {
    BrokerInfoListMXView(brokers.map(BrokerInfoMXView.apply).toArray)
  }

}

trait ProxyStatusMXBean extends WsProxyJmxBean {

  def isHttpEnabled: Boolean
  def isHttpsEnabled: Boolean
  def isBasicAuthEnabled: Boolean
  def isOpenIDConnectEnabled: Boolean
  def getHttpPort: Int
  def getHttpsPort: Int
  def getSessionStateTopicName: String
  def getUpSince: String
  def getUptimeMillis: Long
  def getBrokerInfoListMXView: BrokerInfoListMXView

}

class ProxyStatusMXBeanActor(
    appCfg: AppCfg,
    ctx: ActorContext[ProxyStatusCommand]
) extends MXBeanActor[ProxyStatusCommand](ctx)
    with ProxyStatusMXBean {

  // Volatile because JMX and the actor model access from different threads.
  @volatile private[this] var clusterInfo: List[BrokerInfo] = List.empty

  private[this] val started = LocalDateTime.now()

  override def getBrokerInfoListMXView = BrokerInfoListMXView(clusterInfo)

  override def isHttpEnabled: Boolean = appCfg.server.isPlainEnabled

  override def isHttpsEnabled: Boolean = appCfg.server.isSslEnabled

  override def isBasicAuthEnabled: Boolean = appCfg.server.isBasicAuthEnabled

  override def isOpenIDConnectEnabled: Boolean =
    appCfg.server.isOpenIdConnectEnabled

  override def getHttpPort: Int = appCfg.server.port

  override def getHttpsPort: Int =
    appCfg.server.ssl.flatMap(_.port).getOrElse(0)

  override def getSessionStateTopicName: String =
    appCfg.sessionHandler.sessionStateTopicName.value

  override def getUpSince: String =
    started.format(java.time.format.DateTimeFormatter.ISO_DATE_TIME)

  override def getUptimeMillis: Long = {
    val nowMillis   = LocalDateTime.now().toInstant(ZoneOffset.UTC).toEpochMilli
    val startMillis = started.toInstant(ZoneOffset.UTC).toEpochMilli

    nowMillis - startMillis
  }

  override def onMessage(msg: ProxyStatusCommand) =
    msg match {
      case UpdateKafkaClusterInfo(brokers, replyTo) =>
        log.trace(s"Adding ${brokers.size} brokers to cluster info")
        clusterInfo = brokers
        replyTo ! KafkaClusterInfoUpdated
        Behaviors.same

      case ClearBrokers(replyTo) =>
        log.trace("Clearing cluster info because no data was received")
        clusterInfo = List.empty
        replyTo ! BrokersCleared
        Behaviors.same
    }
}

object ProxyStatusMXBeanActor {

  def apply(appCfg: AppCfg): Behavior[ProxyStatusCommand] =
    Behaviors.setup(ctx => new ProxyStatusMXBeanActor(appCfg, ctx))
}

object ProxyStatusProtocol {

  sealed trait ProxyStatusCommand
  sealed trait ProxyStatusResponse

  case class ClearBrokers(replyTo: ActorRef[ProxyStatusResponse])
      extends ProxyStatusCommand

  case class UpdateKafkaClusterInfo(
      brokers: List[BrokerInfo],
      replyTo: ActorRef[ProxyStatusResponse]
  ) extends ProxyStatusCommand

  case object KafkaClusterInfoUpdated extends ProxyStatusResponse
  case object BrokersCleared          extends ProxyStatusResponse
}
