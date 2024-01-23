package net.scalytica.kafka.wsproxy.session

import net.scalytica.kafka.wsproxy.logging.WithProxyLogger
import net.scalytica.kafka.wsproxy.config.Configuration.{
  ClientSpecificLimitCfg,
  ConsumerSpecificLimitCfg,
  ProducerSpecificLimitCfg
}
import net.scalytica.kafka.wsproxy.models.{FullClientId, WsGroupId, WsServerId}

/**
 * Defines the common attributes and functions for session data used to keep
 * track of active Kafka clients.
 */
sealed trait Session { self: WithProxyLogger =>

  def sessionId: SessionId
  def maxConnections: Int
  // val rateLimit: Int

  def updateConfig(config: ClientSpecificLimitCfg): Session

  def instances: Set[ClientInstance]

  def canOpenSocket: Boolean = {
    log.trace(
      "Validating if session can open connection:" +
        s" instances=${instances.size + 1} maxConnections=$maxConnections"
    )
    maxConnections == 0 || (instances.size + 1) <= maxConnections
  }

  def hasInstance(clientId: FullClientId): Boolean = {
    instances.exists(_.id == clientId)
  }

  def updateInstances(updated: Set[ClientInstance]): Session

  def addInstance(instance: ClientInstance): SessionOpResult

  def removeInstance(clientId: FullClientId): SessionOpResult = {
    if (hasInstance(clientId)) {
      InstanceRemoved(
        updateInstances(instances.filterNot(_.id == clientId))
      )
    } else {
      InstanceDoesNotExists(self)
    }
  }

  def removeInstancesFromServerId(serverId: WsServerId): Session = {
    updateInstances(instances.filterNot(_.serverId == serverId))
  }
}

/**
 * Defines the shape of a Kafka consumer session with 1 or more clients.
 *
 * @param sessionId
 *   The session ID for this session
 * @param groupId
 *   The group ID this session applies to
 * @param maxConnections
 *   The maximum number of allowed connections. 0 means unlimited
 * @param instances
 *   The active client instances
 */
case class ConsumerSession private (
    sessionId: SessionId,
    groupId: WsGroupId,
    maxConnections: Int = 2,
    instances: Set[ClientInstance] = Set.empty
) extends Session
    with WithProxyLogger {

  require(instances.forall(_.isInstanceOf[ConsumerInstance]))

  def updateConfig(config: ClientSpecificLimitCfg): Session = {
    config match {
      case ConsumerSpecificLimitCfg(gid, _, maxCons, _)
          if maxCons.nonEmpty && groupId == gid =>
        copy(maxConnections = maxCons.getOrElse(maxConnections))
      case _ => this
    }
  }

  override def updateInstances(updated: Set[ClientInstance]): Session = {
    copy(instances = updated)
  }

  override def addInstance(instance: ClientInstance): SessionOpResult = {
    log.trace(s"Attempting to add consumer client: $instance")
    instance match {
      case ci: ConsumerInstance =>
        if (hasInstance(ci.id)) InstanceExists(this)
        else {
          if (canOpenSocket) {
            InstanceAdded(updateInstances(instances + ci))
          } else {
            log.warn(s"Client limit was reached for consumer $instance")
            InstanceLimitReached(this)
          }
        }

      case _ =>
        InstanceTypeForSessionIncorrect(this)
    }
  }
}

/**
 * Defines the shape of a Kafka producer session with 1 or more clients.
 *
 * @param sessionId
 *   The session ID for this session
 * @param maxConnections
 *   The maximum number of allowed connections. 0 means unlimited
 * @param instances
 *   The active client instances
 */
case class ProducerSession(
    sessionId: SessionId,
    maxConnections: Int = 1,
    instances: Set[ClientInstance] = Set.empty
) extends Session
    with WithProxyLogger {

  require(instances.forall(_.isInstanceOf[ProducerInstance]))

  def updateConfig(config: ClientSpecificLimitCfg): Session = {
    config match {
      case ProducerSpecificLimitCfg(pid, _, maxCons)
          if maxCons.nonEmpty && sessionId == SessionId(pid) =>
        copy(maxConnections = maxCons.getOrElse(maxConnections))
      case _ => this
    }
  }

  override def updateInstances(updated: Set[ClientInstance]): Session = {
    copy(instances = updated)
  }

  override def addInstance(instance: ClientInstance): SessionOpResult = {
    log.trace(s"Attempting to add producer client: $instance")
    instance match {
      case pi: ProducerInstance =>
        if (canOpenSocket) {
          InstanceAdded(updateInstances(instances + pi))
        } else {
          log.warn(s"Client limit was reached for producer $instance")
          InstanceLimitReached(this)
        }

      case _ =>
        InstanceTypeForSessionIncorrect(this)
    }
  }
}
