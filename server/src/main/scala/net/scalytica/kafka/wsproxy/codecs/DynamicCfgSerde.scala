package net.scalytica.kafka.wsproxy.codecs

import net.scalytica.kafka.wsproxy.config.Configuration
import net.scalytica.kafka.wsproxy.config.Configuration.DynamicCfg
import net.scalytica.kafka.wsproxy.errors.InvalidDynamicCfg
import net.scalytica.kafka.wsproxy.logging.WithProxyLogger

import scala.util.{Failure, Success, Try}

/**
 * Serde implementation for [[DynamicCfg]] types. Since these are config objects
 * derived from HOCON config objects, this implementation will serialize and
 * deserialize the object to/from a HOCON string.
 */
class DynamicCfgSerde
    extends StringBasedSerde[DynamicCfg]
    with WithProxyLogger {

  override def serialize(topic: String, data: DynamicCfg) =
    Option(data).map(d => ser.serialize(topic, d.asHoconString())).orNull

  override def deserialize(topic: String, data: Array[Byte]) = {
    Option(data).map { d =>
      val str = des.deserialize(topic, d)

      log.trace(s"Deserialized dynamic config from topic $topic to:\n$str")

      Try(Configuration.loadDynamicCfgString(str)) match {
        case Success(value) => value
        case Failure(err) =>
          throw InvalidDynamicCfg(err.getMessage, Option(err))
      }
    }.orNull
  }

}
