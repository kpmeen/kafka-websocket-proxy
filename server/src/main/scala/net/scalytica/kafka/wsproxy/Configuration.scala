package net.scalytica.kafka.wsproxy

import java.nio.file.Path

import com.typesafe.config.{Config, ConfigFactory}
import net.scalytica.kafka.wsproxy.models.{TopicName, WsServerId}
import org.apache.kafka.clients.CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG
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

  implicit val stringAsKafkaBootstrapUrls: ConfigReader[KafkaBootstrapHosts] =
    ConfigReader.fromString { str =>
      val urls = str.split(",").map(_.trim).toList
      Right(KafkaBootstrapHosts(urls))
    }

  implicit val stringAsServerId: ConfigReader[WsServerId] =
    ConfigReader.fromString(str => Right(WsServerId(str)))

  implicit val stringAsTopicName: ConfigReader[TopicName] =
    ConfigReader.fromString(str => Right(TopicName(str)))

  final case class AppCfg(
      server: ServerCfg,
      kafkaClient: KafkaClientCfg,
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

  final case class KafkaBootstrapHosts(hosts: List[String]) {
    def mkString(): String = hosts.mkString(",")

    def hostsString: List[String] = hosts.map(_.takeWhile(_ != ':'))
  }

  final case class ServerCfg(
      serverId: WsServerId,
      bindInterface: String,
      port: Int,
      ssl: Option[ServerSslCfg]
  )

  final case class ServerSslCfg(
      sslOnly: Boolean,
      bindInterface: Option[String],
      port: Option[Int],
      keystoreLocation: Option[String],
      keystorePassword: Option[String]
  ) {

    def liftKeystorePassword: Array[Char] =
      keystorePassword.getOrElse("").toCharArray

  }

  final case class KafkaClientCfg(
      brokerResolutionTimeout: FiniteDuration,
      bootstrapHosts: KafkaBootstrapHosts,
      schemaRegistry: Option[SchemaRegistryCfg],
      monitoringEnabled: Boolean,
      properties: Map[String, AnyRef],
      confluentMonitoring: Option[ConfluentMonitoringCfg]
  )

  final case class SchemaRegistryCfg(
      url: String,
      autoRegisterSchemas: Boolean,
      properties: Map[String, AnyRef] = Map.empty
  )

  final case class ConfluentMonitoringCfg(
      bootstrapHosts: KafkaBootstrapHosts,
      properties: Map[String, AnyRef]
  ) {

    def asPrefixedProperties: Map[String, AnyRef] =
      ConfluentMonitoringCfg
        .withConfluentMonitoringPrefix(bootstrapHosts, properties)

  }

  object ConfluentMonitoringCfg {
    val MonitoringPrefix = "confluent.monitoring.interceptor"

    val BootstrapServersKey = s"$MonitoringPrefix.$BOOTSTRAP_SERVERS_CONFIG"

    def withConfluentMonitoringPrefix(
        bootstrapHosts: KafkaBootstrapHosts,
        props: Map[String, AnyRef]
    ): Map[String, AnyRef] = {
      props.map {
        case (key, value) =>
          s"${ConfluentMonitoringCfg.MonitoringPrefix}.$key" -> value
      } + (BootstrapServersKey -> bootstrapHosts.hosts.mkString(","))
    }
  }

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
      sessionStateTopicName: TopicName,
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
