package net.scalytica.kafka.wsproxy

import java.nio.file.Path

import com.typesafe.config.{Config, ConfigFactory}
import pureconfig.loadConfigOrThrow
import pureconfig.generic.auto._

import scala.concurrent.duration.FiniteDuration

object Configuration {

  private[this] val CfgRootKey = "kafka.ws.proxy"

  final case class AppCfg(
      server: ServerCfg,
      consumer: ConsumerCfg,
      commitHandler: CommitHandlerCfg
  ) {

    lazy val isRateLimitEnabled: Boolean = consumer.defaultRateLimit > 0
    lazy val isBatchingEnabled: Boolean  = consumer.defaultBatchSize > 0

    lazy val isRateLimitAndBatchEnabled: Boolean =
      isRateLimitEnabled && isBatchingEnabled

  }

  final case class ServerCfg(
      port: Int,
      kafkaBootstrapUrls: Seq[String]
  )

  final case class ConsumerCfg(
      defaultRateLimit: Long,
      defaultBatchSize: Int
  )

  final case class CommitHandlerCfg(
      maxStackSize: Int,
      autoCommitEnabled: Boolean,
      autoCommitInterval: FiniteDuration,
      autoCommitMaxAge: FiniteDuration
  )

  def load(): AppCfg = loadConfigOrThrow[AppCfg](CfgRootKey)

  def loadFrom(arg: (String, Any)*): AppCfg = {
    loadString(arg.map(t => s"${t._1} = ${t._2}").mkString("\n"))
  }

  def loadString(str: String): AppCfg =
    loadConfig(ConfigFactory.parseString(str))

  def loadConfig(cfg: Config): AppCfg =
    loadConfigOrThrow[AppCfg](cfg, CfgRootKey)

  def loadFile(file: Path): AppCfg =
    try {
      loadConfigOrThrow[AppCfg](file, CfgRootKey)
    } catch {
      case ex: Throwable =>
        ex.printStackTrace()
        throw ex
    }
}
