package net.scalytica.kafka.wsproxy.config

import com.typesafe.config.{Config, ConfigFactory}
import net.scalytica.kafka.wsproxy.errors.ConfigurationError
import net.scalytica.kafka.wsproxy.logging.WithProxyLogger
import net.scalytica.kafka.wsproxy.models.{
  TopicName,
  WsClientId,
  WsGroupId,
  WsServerId
}
import org.apache.kafka.clients.CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG
import pureconfig.error.ConfigReaderFailures
import pureconfig.generic.auto._
import pureconfig.{ConfigReader, ConfigSource}

import java.nio.file.Path
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters._

object Configuration extends WithProxyLogger {

  private[this] val CfgRootKey = "kafka.ws.proxy"

  private[this] def typesafeConfigToMap(cfg: Config): Map[String, AnyRef] = {
    cfg.entrySet().asScala.map(e => e.getKey -> e.getValue.unwrapped).toMap
  }

  implicit lazy val configAsMap: ConfigReader[Map[String, AnyRef]] =
    ConfigReader
      .fromCursor(_.asObjectCursor.map(_.objValue.toConfig))
      .map(typesafeConfigToMap)

  implicit lazy val stringAsKafkaUrls: ConfigReader[KafkaBootstrapHosts] =
    ConfigReader.fromNonEmptyString { str =>
      val urls = str.split(',').map(_.trim).toList
      Right(KafkaBootstrapHosts(urls))
    }

  implicit lazy val stringAsServerId: ConfigReader[WsServerId] =
    ConfigReader.fromString(str => Right(WsServerId(str)))

  implicit lazy val stringAsClientId: ConfigReader[WsClientId] =
    ConfigReader.fromString(str => Right(WsClientId(str)))

  implicit lazy val stringAsGroupId: ConfigReader[WsGroupId] =
    ConfigReader.fromString(str => Right(WsGroupId(str)))

  implicit lazy val stringAsTopicName: ConfigReader[TopicName] =
    ConfigReader.fromString(str => Right(TopicName(str)))

  implicit lazy val schemaRegistryCfgReader = {
    ConfigReader.fromCursor[Option[SchemaRegistryCfg]] { cursor =>
      val urlCursor = cursor.fluent.at("url").asString
      urlCursor match {
        case Left(_) =>
          Right[ConfigReaderFailures, Option[SchemaRegistryCfg]](None)

        case Right(url) =>
          if (url.nonEmpty) {
            val autoReg = cursor.fluent
              .at("auto-register-schemas")
              .asBoolean
              .getOrElse(true)
            val props = cursor.fluent
              .at("properties")
              .asObjectCursor
              .map(coc => typesafeConfigToMap(coc.objValue.toConfig))
              .getOrElse(Map.empty)

            Right[ConfigReaderFailures, Option[SchemaRegistryCfg]](
              Option(
                SchemaRegistryCfg(
                  url = url,
                  autoRegisterSchemas = autoReg,
                  properties = props
                )
              )
            )
          } else {
            Right[ConfigReaderFailures, Option[SchemaRegistryCfg]](None)
          }
      }
    }
  }

  implicit lazy val customJwtCfgReader = {
    ConfigReader.fromCursor[Option[CustomJwtCfg]] { c =>
      c.fluent
        .at("jwt-kafka-username-key")
        .asString
        .toOption
        .flatMap { u =>
          c.fluent.at("jwt-kafka-password-key").asString.toOption.map { p =>
            Right[ConfigReaderFailures, Option[CustomJwtCfg]](
              Option(CustomJwtCfg(u, p))
            )
          }
        }
        .getOrElse {
          Right[ConfigReaderFailures, Option[CustomJwtCfg]](None)
        }
    }
  }

  implicit lazy val jmxManagerCfgReader = {
    ConfigReader.fromCursor[JmxManagerConfig] { c =>
      c.fluent
        .at("proxy", "status", "interval")
        .cursor
        .flatMap(cc => ConfigReader.finiteDurationConfigReader.from(cc))
        .map(fd => JmxManagerConfig(fd))
    }
  }

  private[this] val consLimCfgReader: ConfigReader[ConsumerSpecificLimitCfg] = {
    ConfigReader.forProduct4(
      keyA0 = "group-id",
      keyA1 = "messages-per-second",
      keyA2 = "max-connections",
      keyA3 = "batch-size"
    )(ConsumerSpecificLimitCfg.apply)
  }

  private[this] val prodLimCfgReader: ConfigReader[ProducerSpecificLimitCfg] = {
    ConfigReader.forProduct3(
      keyA0 = "client-id",
      keyA1 = "messages-per-second",
      keyA2 = "max-connections"
    )(ProducerSpecificLimitCfg.apply)
  }

  implicit lazy val specLimCfgReader: ConfigReader[ClientSpecificLimitCfg] = {
    ConfigReader.fromCursor { cursor =>
      cursor.fluent
        .at("group-id")
        .asString
        .toOption
        .map { _ =>
          consLimCfgReader.from(cursor)
        }
        .getOrElse {
          prodLimCfgReader.from(cursor)
        }
    }
  }

  final case class KafkaBootstrapHosts(hosts: List[String]) {
    def mkString(): String = hosts.mkString(",")

    def hostStrings: List[String] = hosts.map(_.takeWhile(_ != ':'))
  }

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
      props.map { case (key, value) =>
        s"${ConfluentMonitoringCfg.MonitoringPrefix}.$key" -> value
      } + (BootstrapServersKey -> bootstrapHosts.hosts.mkString(","))
    }
  }

  final case class KafkaClientCfg(
      brokerResolutionTimeout: FiniteDuration,
      bootstrapHosts: KafkaBootstrapHosts,
      schemaRegistry: Option[SchemaRegistryCfg],
      monitoringEnabled: Boolean,
      properties: Map[String, AnyRef],
      confluentMonitoring: Option[ConfluentMonitoringCfg]
  )

  final case class AdminClientCfg(
      kafkaClientProperties: Map[String, AnyRef]
  )

  sealed trait ClientSpecificLimitCfg {
    val id: String
    val messagesPerSecond: Option[Int]
    val maxConnections: Option[Int]
    val batchSize: Option[Int]
  }

  final case class ConsumerSpecificLimitCfg(
      groupId: WsGroupId,
      messagesPerSecond: Option[Int],
      maxConnections: Option[Int],
      batchSize: Option[Int]
  ) extends ClientSpecificLimitCfg {
    override val id = groupId.value
  }

  final case class ProducerSpecificLimitCfg(
      clientId: WsClientId,
      messagesPerSecond: Option[Int],
      maxConnections: Option[Int]
  ) extends ClientSpecificLimitCfg {

    override val id        = clientId.value
    override val batchSize = None

  }

  final case class ClientLimitsCfg(
      defaultMessagesPerSecond: Int = 0,
      defaultMaxConnectionsPerClient: Int = 0,
      defaultBatchSize: Int = 0,
      clientSpecificLimits: Seq[ClientSpecificLimitCfg] = Seq.empty
  ) {

    def forProducer(clientId: WsClientId): Option[ClientSpecificLimitCfg] = {
      clientSpecificLimits.find {
        case p: ProducerSpecificLimitCfg => p.clientId == clientId
        case _                           => false
      }
    }

    def forConsumer(groupId: WsGroupId): Option[ClientSpecificLimitCfg] = {
      clientSpecificLimits.find {
        case c: ConsumerSpecificLimitCfg => c.groupId == groupId
        case _                           => false
      }
    }

  }

  final case class ConsumerCfg(
      limits: ClientLimitsCfg,
      kafkaClientProperties: Map[String, AnyRef]
  )

  final case class ProducerCfg(
      limits: ClientLimitsCfg,
      kafkaClientProperties: Map[String, AnyRef]
  )

  final case class SessionHandlerCfg(
      sessionStateTopicInitTimeout: FiniteDuration,
      sessionStateTopicInitRetries: Int,
      sessionStateTopicInitRetryInterval: FiniteDuration,
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

  final case class ServerSslCfg(
      enabled: Boolean,
      sslOnly: Boolean,
      bindInterface: Option[String],
      port: Option[Int],
      keystoreLocation: Option[String],
      keystorePassword: Option[String]
  ) {

    def liftKeystorePassword: Array[Char] =
      keystorePassword.getOrElse("").toCharArray

  }

  final case class BasicAuthCfg(
      username: Option[String],
      password: Option[String],
      realm: Option[String],
      enabled: Boolean = false
  ) {
    if (enabled)
      require(
        username.isDefined && password.isDefined && realm.isDefined,
        "username, password and realm are required when basic auth is enabled"
      )
  }

  final case class CustomJwtCfg(
      jwtKafkaUsernameKey: String,
      jwtKafkaPasswordKey: String,
      kafkaTokenAuthOnly: Boolean = false
  )

  final case class OpenIdConnectCfg(
      wellKnownUrl: Option[String],
      audience: Option[String],
      realm: Option[String],
      enabled: Boolean = false,
      requireHttps: Boolean = true,
      revalidationInterval: FiniteDuration,
      revalidationErrorsLimit: Int = -1,
      customJwt: Option[CustomJwtCfg] = None,
      allowDetailedLogging: Boolean = false
  ) {
    if (enabled) {
      require(
        wellKnownUrl.isDefined && audience.isDefined,
        "well-known-url and audience must be defined when openid is enabled"
      )
    }

    def isKafkaTokenAuthOnlyEnabled: Boolean =
      customJwt.exists(_.kafkaTokenAuthOnly)
  }

  final case class JmxConfig(manager: JmxManagerConfig)

  case class JmxManagerConfig(proxyStatusInterval: FiniteDuration)

  final case class ServerCfg(
      serverId: WsServerId,
      bindInterface: String,
      port: Int,
      ssl: Option[ServerSslCfg],
      brokerResolutionTimeout: FiniteDuration,
      brokerResolutionRetries: Int,
      brokerResolutionRetryInterval: FiniteDuration,
      secureHealthCheckEndpoint: Boolean,
      basicAuth: Option[BasicAuthCfg],
      openidConnect: Option[OpenIdConnectCfg],
      jmx: JmxConfig
  ) {

    def isSslEnabled: Boolean = ssl.exists(_.enabled)

    def isPlainEnabled: Boolean = !ssl.exists(_.sslOnly)

    def isAuthEnabled: Boolean = isBasicAuthEnabled || isOpenIdConnectEnabled

    def isAuthSecurelyEnabled: Boolean =
      isAuthEnabled && isSslEnabled && !isPlainEnabled

    def isBasicAuthEnabled: Boolean =
      basicAuth.isDefined && basicAuth.exists(_.enabled)

    def isOpenIdConnectEnabled: Boolean =
      openidConnect.isDefined && openidConnect.exists(_.enabled)

    def isKafkaTokenAuthOnlyEnabled: Boolean =
      openidConnect.exists(_.isKafkaTokenAuthOnlyEnabled)

    def customJwtKafkaCredsKeys: Option[(String, String)] = {
      openidConnect.flatMap { oidc =>
        oidc.customJwt.map { custom =>
          (custom.jwtKafkaUsernameKey, custom.jwtKafkaPasswordKey)
        }
      }
    }

  }

  final case class AppCfg(
      server: ServerCfg,
      kafkaClient: KafkaClientCfg,
      adminClient: AdminClientCfg,
      consumer: ConsumerCfg,
      producer: ProducerCfg,
      sessionHandler: SessionHandlerCfg,
      commitHandler: CommitHandlerCfg
  ) {

    lazy val isRateLimitEnabled: Boolean =
      consumer.limits.defaultMessagesPerSecond > 0
    lazy val isBatchingEnabled: Boolean = consumer.limits.defaultBatchSize > 0

    lazy val isRateLimitAndBatchEnabled: Boolean =
      isRateLimitEnabled && isBatchingEnabled

  }

  def load(): AppCfg = {
    logger.debug("Loading default config")
    ConfigSource.default.at(CfgRootKey).loadOrThrow[AppCfg]
  }

  def loadFrom(arg: (String, Any)*): AppCfg = {
    logger.debug("Loading config from key/value pairs")
    loadString(arg.map(t => s"${t._1} = ${t._2}").mkString("\n"))
  }

  /**
   * Loads and resolves the default application.conf
   * @return
   *   The a resolved instance of Typesafe Config
   */
  def loadTypesafeConfig(): Config = {
    logger.debug("Loading default typesafe configuration")
    ConfigSource.default.config() match {
      case Right(config) => config
      case Left(errors)  => throw ConfigurationError(errors.prettyPrint())
    }
  }

  def loadString(str: String): AppCfg = {
    logger.debug(s"Loading configuration from string")
    loadConfig(ConfigFactory.parseString(str))
  }

  def loadConfig(cfg: Config): AppCfg = {
    logger.debug(s"Loading configuration from ${classOf[Config]} instance")
    ConfigSource.fromConfig(cfg).at(CfgRootKey).loadOrThrow[AppCfg]
  }

  def loadFile(file: Path): AppCfg = {
    logger.debug(s"Loading configuration file at path $file")
    ConfigSource.file(file).at(CfgRootKey).loadOrThrow[AppCfg]
  }
}
