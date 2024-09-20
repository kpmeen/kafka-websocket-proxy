package net.scalytica.kafka.wsproxy.config

import java.nio.file.Path

import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters._

import net.scalytica.kafka.wsproxy.errors.ConfigurationError
import net.scalytica.kafka.wsproxy.logging.WithProxyLogger
import net.scalytica.kafka.wsproxy.models.TopicName
import net.scalytica.kafka.wsproxy.models.WsGroupId
import net.scalytica.kafka.wsproxy.models.WsProducerId
import net.scalytica.kafka.wsproxy.models.WsServerId

import com.typesafe.config.Config
import com.typesafe.config.ConfigRenderOptions
import org.apache.kafka.clients.CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG
import org.apache.kafka.common.config.SaslConfigs
import pureconfig.ConfigReader
import pureconfig.ConfigSource
import pureconfig.ConfigWriter
import pureconfig.error.ConfigReaderFailures
import pureconfig.generic.auto._

// scalastyle:off number.of.methods
trait Configuration extends WithProxyLogger {

  protected val CfgRootKey: String = "kafka.ws.proxy"

  private[this] def lightbendConfigToMap(cfg: Config): Map[String, String] = {
    cfg
      .entrySet()
      .asScala
      .map(e => e.getKey -> e.getValue.unwrapped().toString)
      .toMap
  }

  implicit lazy val configAsMap: ConfigReader[Map[String, String]] =
    ConfigReader
      .fromCursor(_.asObjectCursor.map(_.objValue.toConfig))
      .map(lightbendConfigToMap)

  implicit lazy val stringAsKafkaUrls: ConfigReader[KafkaBootstrapHosts] =
    ConfigReader.fromNonEmptyString { str =>
      val urls = str.split(',').map(_.trim).toList
      Right(KafkaBootstrapHosts(urls))
    }

  implicit lazy val stringAsServerId: ConfigReader[WsServerId] =
    ConfigReader.fromString(str => Right(WsServerId(str)))

  implicit lazy val stringAsGroupId: ConfigReader[WsGroupId] =
    ConfigReader.fromString(str => Right(WsGroupId(str)))

  implicit lazy val stringAsWsProducerId: ConfigReader[WsProducerId] =
    ConfigReader.fromString(str => Right(WsProducerId(str)))

  implicit lazy val stringAsTopicName: ConfigReader[TopicName] =
    ConfigReader.fromString(str => Right(TopicName(str)))

  implicit lazy val schemaRegistryCfgReader
      : ConfigReader[Option[SchemaRegistryCfg]] = {
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
              .map(coc => lightbendConfigToMap(coc.objValue.toConfig))
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

  implicit lazy val customJwtCfgReader: ConfigReader[Option[CustomJwtCfg]] = {
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

  implicit lazy val jmxManagerCfgReader: ConfigReader[JmxManagerConfig] = {
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
      keyA0 = "producer-id",
      keyA1 = "messages-per-second",
      keyA2 = "max-connections"
    )(ProducerSpecificLimitCfg.apply)
  }

  implicit lazy val specLimCfgReader: ConfigReader[ClientSpecificLimitCfg] = {
    consLimCfgReader.orElse(prodLimCfgReader)
  }

  implicit lazy val dynamicCfgReader: ConfigReader[DynamicCfg] = {
    // Currently only ClientSpecificLimitCfg types are dynamic
    specLimCfgReader.map(dcfg => dcfg: DynamicCfg)
  }

  implicit lazy val groupIdAsString: ConfigWriter[WsGroupId] =
    ConfigWriter.toString[WsGroupId](_.value)

  implicit lazy val producerIdAsString: ConfigWriter[WsProducerId] =
    ConfigWriter.toString[WsProducerId](_.value)

  case class KafkaBootstrapHosts(hosts: List[String]) {
    def mkString(): String = hosts.mkString(",")

    def hostStrings: List[String] = hosts.map(_.takeWhile(_ != ':'))
  }

  case class SchemaRegistryCfg(
      url: String,
      autoRegisterSchemas: Boolean,
      properties: Map[String, String] = Map.empty
  )

  case class ConfluentMonitoringCfg(
      bootstrapHosts: KafkaBootstrapHosts,
      properties: Map[String, String]
  ) {

    def asPrefixedProperties: Map[String, String] =
      ConfluentMonitoringCfg
        .withConfluentMonitoringPrefix(bootstrapHosts, properties)

  }

  object ConfluentMonitoringCfg {
    val MonitoringPrefix: String = "confluent.monitoring.interceptor"

    val BootstrapServersKey: String =
      s"$MonitoringPrefix.$BOOTSTRAP_SERVERS_CONFIG"

    def withConfluentMonitoringPrefix(
        bootstrapHosts: KafkaBootstrapHosts,
        props: Map[String, String]
    ): Map[String, String] = {
      props.map { case (key, value) =>
        s"${ConfluentMonitoringCfg.MonitoringPrefix}.$key" -> value
      } + (BootstrapServersKey -> bootstrapHosts.hosts.mkString(","))
    }
  }

  case class KafkaClientCfg(
      brokerResolutionTimeout: FiniteDuration,
      bootstrapHosts: KafkaBootstrapHosts,
      schemaRegistry: Option[SchemaRegistryCfg],
      monitoringEnabled: Boolean,
      properties: Map[String, String],
      confluentMonitoring: Option[ConfluentMonitoringCfg]
  )

  case class AdminClientCfg(
      kafkaClientProperties: Map[String, String]
  )

  sealed trait DynamicCfg {
    val id: String

    def asHoconString(useJson: Boolean = false): String
  }

  sealed trait ClientSpecificLimitCfg extends DynamicCfg {
    val messagesPerSecond: Option[Int]
    val maxConnections: Option[Int]
    val batchSize: Option[Int]
  }

  case class ConsumerSpecificLimitCfg(
      groupId: WsGroupId,
      messagesPerSecond: Option[Int],
      maxConnections: Option[Int],
      batchSize: Option[Int]
  ) extends ClientSpecificLimitCfg {
    override val id = groupId.value

    override def asHoconString(useJson: Boolean = false): String = {
      ConfigWriter[ConsumerSpecificLimitCfg]
        .to(this)
        .render(ConfigRenderOptions.concise().setJson(useJson))
    }
  }

  case class ProducerSpecificLimitCfg(
      producerId: WsProducerId,
      messagesPerSecond: Option[Int],
      maxConnections: Option[Int]
  ) extends ClientSpecificLimitCfg {

    override val id: String                 = producerId.value
    override val batchSize: Option[Nothing] = None

    override def asHoconString(useJson: Boolean = false): String = {
      ConfigWriter[ProducerSpecificLimitCfg]
        .to(this)
        .render(ConfigRenderOptions.concise().setJson(useJson))
    }

  }

  case class ClientLimitsCfg(
      defaultMessagesPerSecond: Int = 0,
      defaultMaxConnectionsPerClient: Int = 0,
      defaultBatchSize: Int = 0,
      clientSpecificLimits: Seq[ClientSpecificLimitCfg] = Seq.empty
  ) {

    lazy val defaultsForProducer: ProducerSpecificLimitCfg =
      ProducerSpecificLimitCfg(
        producerId = WsProducerId("__DEFAULT__"),
        messagesPerSecond = Option(defaultMessagesPerSecond),
        maxConnections = Option(defaultMaxConnectionsPerClient)
      )

    lazy val defaultsForConsumer: ConsumerSpecificLimitCfg =
      ConsumerSpecificLimitCfg(
        groupId = WsGroupId("__DEFAULT__"),
        messagesPerSecond = Option(defaultMessagesPerSecond),
        maxConnections = Option(defaultMaxConnectionsPerClient),
        batchSize = Option(defaultBatchSize)
      )

    def forProducer(
        producerId: WsProducerId
    ): Option[ClientSpecificLimitCfg] = {
      clientSpecificLimits.find {
        case p: ProducerSpecificLimitCfg => p.producerId == producerId
        case _                           => false
      }
    }

    def forConsumer(groupId: WsGroupId): Option[ClientSpecificLimitCfg] = {
      clientSpecificLimits.find {
        case c: ConsumerSpecificLimitCfg => c.groupId == groupId
        case _                           => false
      }
    }

    def addConsumerSpecificLimitCfg(
        cfg: ConsumerSpecificLimitCfg
    ): ClientLimitsCfg = {
      this.copy(clientSpecificLimits = clientSpecificLimits :+ cfg)
    }

    def addProducerSpecificLimitCfg(
        cfg: ProducerSpecificLimitCfg
    ): ClientLimitsCfg = {
      this.copy(clientSpecificLimits = clientSpecificLimits :+ cfg)
    }

  }

  case class ConsumerCfg(
      limits: ClientLimitsCfg,
      kafkaClientProperties: Map[String, String]
  ) {

    def saslMechanism: Option[String] =
      kafkaClientProperties.get(SaslConfigs.SASL_MECHANISM)

    def addConsumerLimitCfg(cfg: ConsumerSpecificLimitCfg): ConsumerCfg = {
      this.copy(limits = limits.addConsumerSpecificLimitCfg(cfg))
    }

  }

  case class ProducerCfg(
      sessionsEnabled: Boolean,
      exactlyOnceEnabled: Boolean,
      limits: ClientLimitsCfg,
      kafkaClientProperties: Map[String, String]
  ) {

    def hasValidTransactionCfg: Boolean = {
      sessionsEnabled && exactlyOnceEnabled
    }

    def saslMechanism: Option[String] =
      kafkaClientProperties.get(SaslConfigs.SASL_MECHANISM)

    def addProducerLimitCfg(cfg: ProducerSpecificLimitCfg): ProducerCfg = {
      this.copy(limits = limits.addProducerSpecificLimitCfg(cfg))
    }

  }

  sealed trait InternalStateTopic {
    val topicInitTimeout: FiniteDuration
    val topicInitRetries: Int
    val topicInitRetryInterval: FiniteDuration
    val topicName: TopicName
    val topicReplicationFactor: Short
    val topicRetention: FiniteDuration
  }

  case class DynamicConfigHandlerCfg(
      enabled: Boolean,
      topicInitTimeout: FiniteDuration,
      topicInitRetries: Int,
      topicInitRetryInterval: FiniteDuration,
      topicName: TopicName,
      topicReplicationFactor: Short,
      topicRetention: FiniteDuration
  ) extends InternalStateTopic

  case class SessionHandlerCfg(
      topicInitTimeout: FiniteDuration,
      topicInitRetries: Int,
      topicInitRetryInterval: FiniteDuration,
      topicName: TopicName,
      topicReplicationFactor: Short,
      topicRetention: FiniteDuration
  ) extends InternalStateTopic

  case class CommitHandlerCfg(
      maxStackSize: Int,
      autoCommitEnabled: Boolean,
      autoCommitInterval: FiniteDuration,
      autoCommitMaxAge: FiniteDuration
  )

  case class ServerSslCfg(
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

  case class BasicAuthCfg(
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

  case class CustomJwtCfg(
      jwtKafkaUsernameKey: String,
      jwtKafkaPasswordKey: String,
      kafkaTokenAuthOnly: Boolean = false
  )

  case class OpenIdConnectCfg(
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

  case class JmxConfig(manager: JmxManagerConfig)

  case class JmxManagerConfig(proxyStatusInterval: FiniteDuration)

  case class AdminEndpointConfig(
      enabled: Boolean,
      bindInterface: String,
      port: Int
  )

  case class ServerCfg(
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
      jmx: JmxConfig,
      admin: AdminEndpointConfig
  ) {

    def isAdminEnabled: Boolean = admin.enabled

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

  case class AllClientSpecifcLimits(
      consumerLimits: Seq[ConsumerSpecificLimitCfg],
      producerLimits: Seq[ProducerSpecificLimitCfg]
  ) {

    def findConsumerSpecificLimitCfg(
        id: String
    ): Option[ConsumerSpecificLimitCfg] = consumerLimits.find(_.id == id)

    def findProducerSpecificLimitCfg(
        id: String
    ): Option[ProducerSpecificLimitCfg] = producerLimits.find(_.id == id)

    def findClientSpecificLimitCfg(
        id: String
    ): Option[ClientSpecificLimitCfg] =
      consumerLimits.find(_.id == id).orElse(producerLimits.find(_.id == id))

  }

  case class AppCfg(
      server: ServerCfg,
      kafkaClient: KafkaClientCfg,
      adminClient: AdminClientCfg,
      consumer: ConsumerCfg,
      producer: ProducerCfg,
      dynamicConfigHandler: DynamicConfigHandlerCfg,
      sessionHandler: SessionHandlerCfg,
      commitHandler: CommitHandlerCfg
  ) {

    lazy val isRateLimitEnabled: Boolean =
      consumer.limits.defaultMessagesPerSecond > 0
    lazy val isBatchingEnabled: Boolean = consumer.limits.defaultBatchSize > 0

    lazy val isRateLimitAndBatchEnabled: Boolean =
      isRateLimitEnabled && isBatchingEnabled

    def addProducerCfg(cfg: ProducerSpecificLimitCfg): AppCfg = {
      this.copy(producer = producer.addProducerLimitCfg(cfg))
    }

    def addConsumerCfg(cfg: ConsumerSpecificLimitCfg): AppCfg = {
      this.copy(consumer = consumer.addConsumerLimitCfg(cfg))
    }

    /** Fast access to the consumer specific limit configs */
    def consumerLimitsCfg: ClientLimitsCfg = consumer.limits

    /** Fast access to the producer specific limit configs */
    def producerLimitsCfg: ClientLimitsCfg = producer.limits

    /**
     * Fetch all limit configs for consumer and producer clients. Including the
     * default configs as a separate config entry, with the correct type, for
     * each client type.
     *
     * @return
     *   An instance of [[AllClientSpecifcLimits]] containing both consumer and
     *   producer limit configs.
     */
    lazy val allClientLimits: AllClientSpecifcLimits = {
      val cl = consumer.limits
      val pl = producer.limits

      val allCons = cl.defaultsForConsumer +: cl.clientSpecificLimits.collect {
        case csl: ConsumerSpecificLimitCfg => csl
      }
      val allProd = pl.defaultsForProducer +: pl.clientSpecificLimits.collect {
        case psl: ProducerSpecificLimitCfg => psl
      }

      AllClientSpecifcLimits(allCons, allProd)
    }

  }

}

object Configuration extends Configuration with WithProxyLogger {

  /**
   * Loads the default application.conf file.
   *
   * @return
   *   an instance of [[AppCfg]]
   */
  def load(): AppCfg = {
    log.debug("Loading default config")
    ConfigSource.default.at(CfgRootKey).loadOrThrow[AppCfg]
  }

  /**
   * Loads and resolves the default application.conf
   *
   * @return
   *   an instance of Lightbend [[Config]].
   */
  def loadLightbendConfig(): Config = {
    log.debug("Loading default Lightbend configuration")
    ConfigSource.default.config() match {
      case Right(config) => config
      case Left(errors)  => throw ConfigurationError(errors.prettyPrint())
    }
  }

  /**
   * Loads an [[AppCfg]] instance from a Lightbend [[Config]] instance.
   *
   * @return
   *   an instance of [[AppCfg]]
   */
  def loadConfig(cfg: Config): AppCfg = {
    log.debug(s"Loading configuration from ${classOf[Config]} instance")
    ConfigSource.fromConfig(cfg).at(CfgRootKey).loadOrThrow[AppCfg]
  }

  /**
   * Loads the configuration from a specific file path.
   *
   * @return
   *   an instance of [[AppCfg]]
   */
  def loadFile(file: Path): AppCfg = {
    log.debug(s"Loading configuration file at path $file")
    ConfigSource.file(file).at(CfgRootKey).loadOrThrow[AppCfg]
  }

  /**
   * Loads a [[DynamicCfg]] instance from a HOCON String.
   *
   * @return
   *   an instance of [[DynamicCfg]]
   */
  def loadDynamicCfgString(str: String): DynamicCfg = {
    ConfigSource.string(str).loadOrThrow[DynamicCfg]
  }
}
