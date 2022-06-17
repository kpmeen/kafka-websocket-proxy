package net.scalytica.kafka.wsproxy.config

import net.scalytica.kafka.wsproxy.config.Configuration.DynamicCfg
import net.scalytica.kafka.wsproxy.config.DynamicConfigHandlerProtocol._
import net.scalytica.kafka.wsproxy.logging.WithProxyLogger

/**
 * Class containing active dynamic configurations. Used as the state object for
 * the [[DynamicConfigHandler]] actor.
 *
 * @param configs
 *   A [[Map]] with String keys and [[DynamicCfg]] values.
 */
case class DynamicConfigurations(
    configs: Map[String, DynamicCfg] = Map.empty
) extends WithProxyLogger {

  def isEmpty: Boolean = configs.isEmpty

  def keys: Set[String] = configs.keySet

  def values: Seq[DynamicCfg] = configs.values.toSeq

  def hasKey(key: String): Boolean = configs.keySet.contains(key)

  def findByKey(key: String): Option[DynamicCfg] = configs.get(key)

  private[config] def update(
      rec: UpdateDynamicConfigRecord
  ): Option[DynamicConfigurations] = {
    findByKey(rec.key) match {
      case Some(existing) =>
        if (existing == rec.value) {
          log.trace(s"Dynamic config for ${rec.key} did not change")
          None
        } else {
          log.trace(s"Updating dynamic config for ${rec.key}")
          Option(DynamicConfigurations(configs.updated(rec.key, rec.value)))
        }

      case None =>
        log.trace(s"Adding dynamic config for ${rec.key}")
        Option(DynamicConfigurations(configs.updated(rec.key, rec.value)))
    }
  }

  private[config] def remove(
      rem: RemoveDynamicConfigRecord
  ): Option[DynamicConfigurations] = {
    if (hasKey(rem.key)) {
      log.trace(s"Removing dynamic config for ${rem.key}")
      Option(DynamicConfigurations(configs.removed(rem.key)))
    } else {
      log.trace(s"Dynamic config with key ${rem.key} could not be found.")
      None
    }
  }

  private[config] def removeAll(): DynamicConfigurations =
    DynamicConfigurations()
}

object DynamicConfigurations {

  def apply(configs: UpdateDynamicConfigRecord*): DynamicConfigurations = {
    DynamicConfigurations(configs.map(c => c.key -> c.value).toMap)
  }

}
