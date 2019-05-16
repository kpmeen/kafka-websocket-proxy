package net.scalytica.kafka.wsproxy

import java.nio.file.Path

import com.typesafe.config.{Config, ConfigFactory}
import pureconfig.{loadConfigOrThrow, ConfigReader}
import pureconfig.generic.auto._

import scala.concurrent.duration.FiniteDuration
import scala.collection.JavaConverters._

object Configuration {

  private[this] val CfgRootKey = "kafka.ws.proxy"

  implicit val configAsMap: ConfigReader[Map[String, AnyRef]] =
    ConfigReader.fromCursor(_.asObjectCursor.right.map(_.value.toConfig)).map {
      cfg =>
        cfg
          .entrySet()
          .asScala
          .map(e => e.getKey -> e.getValue.unwrapped())
          .toMap
    }

  implicit val stringAsKafkaBootstrapUrls: ConfigReader[KafkaBootstrapUrls] =
    ConfigReader.fromString { str =>
      val urls = str.split(",").map(_.trim).toList
      Right(KafkaBootstrapUrls(urls))
    }

  final case class AppCfg(
      server: ServerCfg,
      adminClient: AdminClientCfg,
      consumer: ConsumerCfg,
      producer: ProducerCfg,
      sessionHandler: SessionHandlerCfg,
      commitHandler: CommitHandlerCfg
  ) {

    lazy val isRateLimitEnabled: Boolean = consumer.defaultRateLimit > 0
    lazy val isBatchingEnabled: Boolean  = consumer.defaultBatchSize > 0

    lazy val isRateLimitAndBatchEnabled: Boolean =
      isRateLimitEnabled && isBatchingEnabled

  }

  final case class KafkaBootstrapUrls(urls: List[String]) {
    def mkString(): String = urls.mkString(",")
  }

  final case class ServerCfg(
      serverId: String,
      port: Int,
      kafkaBootstrapUrls: KafkaBootstrapUrls,
      schemaRegistryUrl: Option[String],
      autoRegisterSchemas: Boolean
  )

  final case class AdminClientCfg(
      kafkaClientProperties: Map[String, AnyRef]
  )

  final case class ConsumerCfg(
      defaultRateLimit: Long,
      defaultBatchSize: Int,
      kafkaClientProperties: Map[String, AnyRef]
  )

  final case class ProducerCfg(
      kafkaClientProperties: Map[String, AnyRef]
  )

  final case class SessionHandlerCfg(
      sessionStateTopicName: String,
      sessionStateReplicationFactor: Short,
      sessionStateRetention: FiniteDuration
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

  def loadTypesafeConfig(): Config = ConfigFactory.load()

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
