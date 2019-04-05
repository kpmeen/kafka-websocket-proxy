package net.scalytica.kafka.wsproxy

import java.nio.file.Path

import com.typesafe.config.Config
import pureconfig.loadConfigOrThrow
import pureconfig.generic.auto._

object Configuration {

  private[this] val CfgRootKey = "kafka.websocket.proxy"

  final case class AppConfig(
      kafkaBootstrapUrls: Seq[String],
      defaultRateLimit: Long,
      defaultBatchSize: Int
  ) {

    lazy val isRateLimitEnabled: Boolean = defaultRateLimit > 0
    lazy val isBatchingEnabled: Boolean  = defaultBatchSize > 0

    lazy val isRateLimitAndBatchEnabled: Boolean =
      isRateLimitEnabled && isBatchingEnabled

  }

  def load(): AppConfig = loadConfigOrThrow[AppConfig](CfgRootKey)

  def loadConfig(cfg: Config): AppConfig =
    loadConfigOrThrow[AppConfig](cfg, CfgRootKey)

  def loadFile(file: Path): AppConfig =
    try {
      loadConfigOrThrow[AppConfig](file, CfgRootKey)
    } catch {
      case ex: Throwable =>
        ex.printStackTrace()
        throw ex
    }
}
