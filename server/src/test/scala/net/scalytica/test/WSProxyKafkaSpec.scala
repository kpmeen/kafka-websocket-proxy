package net.scalytica.test

import akka.http.scaladsl.model.headers.BasicHttpCredentials
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.{
  RouteTestTimeout,
  ScalatestRouteTest,
  WSProbe
}
import akka.actor.typed.scaladsl.adapter._
import com.typesafe.config.Config
import io.confluent.kafka.schemaregistry.rest.SchemaRegistryConfig._
import jdk.jshell.spi.ExecutionControl.NotImplementedException
import kafka.server.KafkaConfig._
import io.github.embeddedkafka.schemaregistry.{
  EmbeddedKafka,
  EmbeddedKafkaConfig
}
import net.scalytica.kafka.wsproxy.auth.OpenIdClient
import net.scalytica.kafka.wsproxy.avro.SchemaTypes.AvroProducerRecord
import net.scalytica.kafka.wsproxy.config.Configuration
import net.scalytica.kafka.wsproxy.config.Configuration.{
  AdminClientCfg,
  AppCfg,
  BasicAuthCfg,
  KafkaBootstrapHosts,
  OpenIdConnectCfg,
  SchemaRegistryCfg
}
import net.scalytica.kafka.wsproxy.mapToProperties
import net.scalytica.kafka.wsproxy.models.Formats._
import net.scalytica.kafka.wsproxy.models.{TopicName, WsClientId, WsServerId}
import net.scalytica.kafka.wsproxy.session.{
  SessionHandler,
  SessionHandlerProtocol
}
import net.scalytica.test.TestDataGenerators._
import org.apache.kafka.clients.CommonClientConfigs._
import org.apache.kafka.clients.admin.AdminClientConfig.{
  BOOTSTRAP_SERVERS_CONFIG,
  CLIENT_ID_CONFIG,
  CONNECTIONS_MAX_IDLE_MS_CONFIG,
  REQUEST_TIMEOUT_MS_CONFIG
}
import org.apache.kafka.clients.admin.{AdminClient, NewTopic}
import org.apache.kafka.common.config.SaslConfigs._
import org.apache.kafka.common.config.SslConfigs._
import org.apache.kafka.common.security.auth.SecurityProtocol._
import org.scalatest.Suite
import org.scalatest.matchers.must.Matchers

import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.util.Random

// scalastyle:off magic.number
trait WsProxyKafkaSpec
    extends FileLoader
    with ScalatestRouteTest
    with Matchers
    with EmbeddedKafka {
  self: Suite =>

  val testKeyPass: String         = "scalytica"
  val kafkaUser: String           = "client"
  val kafkaPass: String           = kafkaUser
  val creds: BasicHttpCredentials = BasicHttpCredentials(kafkaUser, kafkaPass)

  implicit val routeTestTimeout = RouteTestTimeout(20 seconds)

  val embeddedKafkaConfig: EmbeddedKafkaConfig = EmbeddedKafkaConfig(
    kafkaPort = 0,
    zooKeeperPort = 0,
    schemaRegistryPort = 0,
    customBrokerProperties = Map(AutoCreateTopicsEnableProp -> "false"),
    customSchemaRegistryProperties = Map(
      KAFKASTORE_TOPIC_REPLICATION_FACTOR_CONFIG -> "1"
    )
  )

  val saslSslPlainJaasConfig: String =
    "listener.name.sasl_ssl.plain.sasl.jaas.config"

  val secureClientProps: Map[String, String] = Map(
    // scalastyle:off line.size.limit
    SASL_MECHANISM                               -> "PLAIN",
    SECURITY_PROTOCOL_CONFIG                     -> SASL_SSL.name,
    SSL_ENDPOINT_IDENTIFICATION_ALGORITHM_CONFIG -> "",
    SSL_TRUSTSTORE_LOCATION_CONFIG -> filePath(
      "/sasl/kafka/client.truststore.jks"
    ).toAbsolutePath.toString,
    SSL_TRUSTSTORE_PASSWORD_CONFIG -> testKeyPass,
    SSL_KEYSTORE_LOCATION_CONFIG -> filePath(
      "/sasl/kafka/client.keystore.jks"
    ).toAbsolutePath.toString,
    SSL_KEYSTORE_PASSWORD_CONFIG -> testKeyPass,
    SSL_KEY_PASSWORD_CONFIG      -> testKeyPass,
    SASL_JAAS_CONFIG -> s"""org.apache.kafka.common.security.plain.PlainLoginModule required username="$kafkaUser" password="$kafkaPass";"""
    // scalastyle:on line.size.limit
  )

  def embeddedKafkaConfigWithSasl: EmbeddedKafkaConfig = {
    val brokerPortPlain  = availablePort
    val brokerPortSecure = availablePort
    val zkp              = availablePort
    val srp              = availablePort

    val brokerSasl =
      "org.apache.kafka.common.security.plain.PlainLoginModule required " +
        """username="admin" """ +
        """password="admin" """ +
        """user_admin="admin" """ +
        """user_broker1="broker1" """ +
        s"""user_$kafkaUser="$kafkaPass";"""

    val listeners =
      s"PLAINTEXT://localhost:$brokerPortPlain," +
        s"SASL_SSL://localhost:$brokerPortSecure"

    EmbeddedKafkaConfig(
      kafkaPort = brokerPortSecure,
      zooKeeperPort = zkp,
      schemaRegistryPort = srp,
      customBrokerProperties = Map(
        ZkConnectProp                          -> s"localhost:$zkp",
        AutoCreateTopicsEnableProp             -> "false",
        AdvertisedListenersProp                -> listeners,
        ListenersProp                          -> listeners,
        InterBrokerListenerNameProp            -> PLAINTEXT.name,
        SaslEnabledMechanismsProp              -> "PLAIN",
        SslEndpointIdentificationAlgorithmProp -> "",
        SslKeystoreLocationProp -> FileLoader
          .filePath("/sasl/kafka/broker1.keystore.jks")
          .toAbsolutePath
          .toString,
        SslKeystorePasswordProp -> testKeyPass,
        SslKeyPasswordProp      -> testKeyPass,
        SslTruststoreLocationProp -> FileLoader
          .filePath("/sasl/kafka/broker1.truststore.jks")
          .toAbsolutePath
          .toString,
        SslTruststorePasswordProp -> testKeyPass,
        saslSslPlainJaasConfig    -> brokerSasl
      ),
      customProducerProperties = secureClientProps,
      customConsumerProperties = secureClientProps
    )
  }

  override def testConfig = defaultTypesafeConfig

  lazy val defaultTypesafeConfig: Config = loadConfig("/application-test.conf")

  lazy val defaultTestAppCfg: AppCfg =
    Configuration.loadConfig(testConfig)

  lazy val defaultTestAppCfgWithServerId: String => AppCfg = (sid: String) =>
    defaultTestAppCfg.copy(
      server = defaultTestAppCfg.server.copy(serverId = WsServerId(sid))
    )

  val basicAuthUser         = "basicAuthUser"
  val basicAuthPass         = "basicAuthPass"
  val basicAuthRealm        = "Test Server"
  val basicHttpCreds        = BasicHttpCredentials(basicAuthUser, basicAuthPass)
  val invalidBasicHttpCreds = BasicHttpCredentials(basicAuthUser, "invalid")

  def basicAuthCredendials(
      useServerBasicAuth: Boolean
  ): Option[BasicAuthCfg] = {
    if (useServerBasicAuth)
      Option(
        BasicAuthCfg(
          username = Option(basicAuthUser),
          password = Option(basicAuthPass),
          realm = Option(basicAuthRealm),
          enabled = true
        )
      )
    else None
  }

  def plainTestConfig(
      useServerBasicAuth: Boolean = false
  ): Configuration.AppCfg = {
    val serverId = s"test-server-${Random.nextInt(50000)}"
    val basicAuthCreds =
      if (useServerBasicAuth)
        Option(
          BasicAuthCfg(
            username = Option(basicAuthUser),
            password = Option(basicAuthPass),
            realm = Option(basicAuthRealm)
          )
        )
      else None

    defaultTestAppCfg.copy(
      server = defaultTestAppCfg.server.copy(
        serverId = WsServerId(serverId),
        basicAuth = basicAuthCreds
      )
    )
  }

  def appTestConfig(
      kafkaPort: Int,
      schemaRegistryPort: Option[Int] = None,
      useServerBasicAuth: Boolean = false,
      serverOpenIdCfg: Option[OpenIdConnectCfg] = None
  ): Configuration.AppCfg = {
    if (useServerBasicAuth || serverOpenIdCfg.isDefined) {
      secureAppTestConfig(
        kafkaPort = kafkaPort,
        schemaRegistryPort = schemaRegistryPort,
        useServerBasicAuth = useServerBasicAuth,
        serverOpenIdCfg = serverOpenIdCfg
      )
    } else {
      plainAppTestConfig(
        kafkaPort = kafkaPort,
        schemaRegistryPort = schemaRegistryPort,
        useServerBasicAuth = useServerBasicAuth,
        serverOpenIdCfg = serverOpenIdCfg
      )
    }
  }

  def plainAppTestConfig(
      kafkaPort: Int,
      schemaRegistryPort: Option[Int] = None,
      useServerBasicAuth: Boolean = false,
      serverOpenIdCfg: Option[OpenIdConnectCfg] = None,
      secureHealthCheckEndpoint: Boolean = true
  ): Configuration.AppCfg = {
    val serverId = s"test-server-${Random.nextInt(50000)}"
    val srUrl =
      schemaRegistryPort.map(_ => s"http://${serverHost(schemaRegistryPort)}")
    val srCfg: Option[SchemaRegistryCfg] =
      defaultTestAppCfg.kafkaClient.schemaRegistry.fold(
        srUrl.map(u => SchemaRegistryCfg(u, autoRegisterSchemas = true))
      )(sr => Option(sr.copy(url = srUrl.getOrElse(sr.url))))

    val basicAuthCreds = basicAuthCredendials(useServerBasicAuth)

    defaultTestAppCfg.copy(
      server = defaultTestAppCfg.server.copy(
        serverId = WsServerId(serverId),
        basicAuth = basicAuthCreds,
        openidConnect = serverOpenIdCfg,
        secureHealthCheckEndpoint = secureHealthCheckEndpoint
      ),
      kafkaClient = defaultTestAppCfg.kafkaClient.copy(
        bootstrapHosts = KafkaBootstrapHosts(List(serverHost(Some(kafkaPort)))),
        schemaRegistry = srCfg
      )
    )
  }

  def secureAppTestConfig(
      kafkaPort: Int,
      schemaRegistryPort: Option[Int] = None,
      useServerBasicAuth: Boolean = false,
      serverOpenIdCfg: Option[OpenIdConnectCfg] = None
  ): Configuration.AppCfg = {
    val serverId = s"test-server-${Random.nextInt(50000)}"
    val srUrl =
      schemaRegistryPort.map(_ => s"http://${serverHost(schemaRegistryPort)}")
    val srCfg: Option[SchemaRegistryCfg] =
      defaultTestAppCfg.kafkaClient.schemaRegistry.fold(
        srUrl.map(u => SchemaRegistryCfg(u, autoRegisterSchemas = true))
      )(sr => Option(sr.copy(url = srUrl.getOrElse(sr.url))))

    val basicAuthCreds = basicAuthCredendials(useServerBasicAuth)

    defaultTestAppCfg.copy(
      server = defaultTestAppCfg.server.copy(
        serverId = WsServerId(serverId),
        basicAuth = basicAuthCreds,
        openidConnect = serverOpenIdCfg
      ),
      kafkaClient = defaultTestAppCfg.kafkaClient.copy(
        bootstrapHosts = KafkaBootstrapHosts(List(serverHost(Some(kafkaPort)))),
        schemaRegistry = srCfg,
        properties = secureClientProps
      ),
      adminClient = AdminClientCfg(secureClientProps),
      consumer = defaultTestAppCfg.consumer
        .copy(kafkaClientProperties = secureClientProps),
      producer = defaultTestAppCfg.producer.copy(
        kafkaClientProperties = secureClientProps
      )
    )
  }

  override val zkSessionTimeoutMs    = 10000
  override val zkConnectionTimeoutMs = 10000
  override val topicCreationTimeout  = 5 seconds

  def initialiseTopic(
      topic: String,
      topicConfig: Map[String, String] = Map.empty,
      partitions: Int = 1,
      replicationFactor: Int = 1,
      isSecure: Boolean = false
  )(implicit config: EmbeddedKafkaConfig): Unit = {
    val baseProps = Map[String, AnyRef](
      BOOTSTRAP_SERVERS_CONFIG       -> s"localhost:${config.kafkaPort}",
      CLIENT_ID_CONFIG               -> "embedded-kafka-admin-client",
      REQUEST_TIMEOUT_MS_CONFIG      -> zkSessionTimeoutMs.toString,
      CONNECTIONS_MAX_IDLE_MS_CONFIG -> zkConnectionTimeoutMs.toString
    )

    val adminProps: java.util.Properties =
      if (isSecure) baseProps ++ secureClientProps else baseProps

    val client = AdminClient.create(adminProps)

    val newTopic = new NewTopic(topic, partitions, replicationFactor.toShort)
      .configs(topicConfig.asJava)

    try {
      val _ = client
        .createTopics(Seq(newTopic).asJava)
        .all
        .get(topicCreationTimeout.length, topicCreationTimeout.unit)
    } catch {
      case je: java.util.concurrent.TimeoutException =>
        fail(
          s"Timed out attempting to create ${if (isSecure) " secure" else ""}" +
            s"topic $topic",
          je
        )
    } finally {
      client.close()
    }

  }

  def initTopic(
      topicName: String,
      partitions: Int = 1,
      isSecure: Boolean = false
  )(
      implicit kcfg: EmbeddedKafkaConfig
  ): Unit =
    initialiseTopic(
      topic = topicName,
      partitions = partitions,
      isSecure = isSecure
    )

  def secureKafkaContext[T](body: EmbeddedKafkaConfig => T): T = {
    implicit val cfg = embeddedKafkaConfigWithSasl
    withRunningKafka(body(cfg))
  }

  def plainServerContext[T](
      body: (EmbeddedKafkaConfig, AppCfg, Route) => T
  ): T =
    withRunningKafkaOnFoundPort(embeddedKafkaConfig) { implicit kcfg =>
      implicit val wsCfg =
        plainAppTestConfig(kafkaPort = kcfg.kafkaPort)
      implicit val oidClient: Option[OpenIdClient] = None
      implicit val sessionHandlerRef               = SessionHandler.init

      val (sdcStream, testRoutes) = TestServerRoutes.wsProxyRoutes
      val ctrl                    = sdcStream.run()

      val res = body(kcfg, wsCfg, testRoutes)

      sessionHandlerRef.shRef.tell(
        SessionHandlerProtocol.StopSessionHandler(system.toTyped.ignoreRef)
      )

      ctrl.shutdown()

      res
    }

  def secureServerContext[T](
      useServerBasicAuth: Boolean = false,
      serverOpenIdCfg: Option[OpenIdConnectCfg] = None,
      secureHealthCheckEndpoint: Boolean = true
  )(
      body: (EmbeddedKafkaConfig, AppCfg, Route) => T
  ): T =
    withRunningKafkaOnFoundPort(embeddedKafkaConfig) { implicit kcfg =>
      implicit val wsCfg = plainAppTestConfig(
        kafkaPort = kcfg.kafkaPort,
        schemaRegistryPort = Some(kcfg.schemaRegistryPort),
        useServerBasicAuth = useServerBasicAuth,
        serverOpenIdCfg = serverOpenIdCfg,
        secureHealthCheckEndpoint = secureHealthCheckEndpoint
      )

      implicit val oidClient = wsCfg.server.openidConnect
        .filter(_.enabled)
        .map(_ => OpenIdClient(wsCfg))
      implicit val sessionHandlerRef = SessionHandler.init

      val (sdcStream, testRoutes) = TestServerRoutes.wsProxyRoutes
      val ctrl                    = sdcStream.run()

      val res = body(kcfg, wsCfg, testRoutes)

      sessionHandlerRef.shRef.tell(
        SessionHandlerProtocol.StopSessionHandler(system.toTyped.ignoreRef)
      )

      ctrl.shutdown()

      res
    }

}

trait WsProxyProducerKafkaSpec
    extends WsProxyKafkaSpec
    with WsProducerClientSpec { self: Suite =>

  case class ProducerContext(
      topicName: TopicName,
      embeddedKafkaConfig: EmbeddedKafkaConfig,
      appCfg: AppCfg,
      route: Route,
      producerProbe: WSProbe
  )

  def plainProducerContext[T](
      topic: String = "test-topic",
      partitions: Int = 1
  )(body: ProducerContext => T): T =
    plainServerContext { (kcfg, appCfg, routes) =>
      initTopic(topic, partitions)(kcfg)

      val ctx = ProducerContext(
        topicName = TopicName(topic),
        embeddedKafkaConfig = kcfg,
        appCfg = appCfg,
        route = routes,
        producerProbe = WSProbe()
      )

      body(ctx)
    }
//  secureServerProducerContext(topic = topic, partitions = partitions)(body)

  def secureServerProducerContext[T](
      topic: String,
      partitions: Int = 1,
      useServerBasicAuth: Boolean = false,
      serverOpenIdCfg: Option[OpenIdConnectCfg] = None
  )(body: ProducerContext => T): T = {
    secureKafkaClusterProducerContext(
      topic,
      partitions,
      useServerBasicAuth,
      serverOpenIdCfg
    )(body)
  }

  def secureKafkaClusterProducerContext[T](
      topic: String = "test-topic",
      partitions: Int = 1,
      useServerBasicAuth: Boolean = false,
      serverOpenIdCfg: Option[OpenIdConnectCfg] = None
  )(body: ProducerContext => T): T = {
    secureKafkaContext { implicit kcfg =>
      implicit val wsCfg =
        secureAppTestConfig(
          kafkaPort = kcfg.kafkaPort,
          schemaRegistryPort = Some(kcfg.schemaRegistryPort),
          useServerBasicAuth = useServerBasicAuth,
          serverOpenIdCfg = serverOpenIdCfg
        )

      implicit val oidClient = wsCfg.server.openidConnect
        .filter(_.enabled)
        .map(_ => OpenIdClient(wsCfg))
      implicit val sessionHandlerRef = SessionHandler.init

      initTopic(topic, partitions, isSecure = true)

      val producerProbe           = WSProbe()
      val (sdcStream, testRoutes) = TestServerRoutes.wsProxyRoutes
      val ctrl                    = sdcStream.run()

      val ctx = ProducerContext(
        topicName = TopicName(topic),
        embeddedKafkaConfig = kcfg,
        appCfg = wsCfg,
        route = testRoutes,
        producerProbe = producerProbe
      )
      val res = body(ctx)

      sessionHandlerRef.shRef.tell(
        SessionHandlerProtocol.StopSessionHandler(system.toTyped.ignoreRef)
      )

      ctrl.shutdown()

      res
    }
  }

}

trait WsProxyConsumerKafkaSpec extends WsProxyProducerKafkaSpec { self: Suite =>

  private[this] val defaultProducerClientId = WsClientId("test-producer-client")

  case class ConsumerContext(
      topicName: TopicName,
      embeddedKafkaConfig: EmbeddedKafkaConfig,
      appCfg: AppCfg,
      route: Route,
      producerProbe: WSProbe,
      consumerProbe: WSProbe
  )

  def plainJsonConsumerContext[T](
      topic: String,
      keyType: Option[FormatType],
      valType: FormatType,
      numMessages: Int = 1,
      partitions: Int = 1,
      prePopulate: Boolean = true,
      withHeaders: Boolean = false
  )(body: ConsumerContext => T): T =
    plainConsumerContext(
      topic = topic,
      messageType = JsonType,
      keyType = keyType,
      valType = valType,
      numMessages = numMessages,
      partitions = partitions,
      prePopulate = prePopulate,
      withHeaders = withHeaders
    )(body)

  def plainAvroConsumerContext[T](
      topic: String,
      keyType: Option[FormatType],
      valType: FormatType,
      numMessages: Int = 1,
      partitions: Int = 1,
      prePopulate: Boolean = true,
      withHeaders: Boolean = false
  )(body: ConsumerContext => T): T =
    plainConsumerContext(
      topic = topic,
      messageType = AvroType,
      keyType = keyType,
      valType = valType,
      numMessages = numMessages,
      partitions = partitions,
      prePopulate = prePopulate,
      withHeaders = withHeaders
    )(body)

  def plainConsumerContext[T, M <: FormatType](
      topic: String,
      messageType: M,
      keyType: Option[FormatType],
      valType: FormatType,
      numMessages: Int = 1,
      partitions: Int = 1,
      prePopulate: Boolean = true,
      withHeaders: Boolean = false
  )(body: ConsumerContext => T): T = {
    plainProducerContext(topic, partitions) { implicit pctx =>
      if (prePopulate) {
        produceForMessageType(
          clientId = defaultProducerClientId,
          messageType = messageType,
          keyType = keyType,
          valType = valType,
          numMessages = numMessages,
          prePopulate = prePopulate,
          withHeaders = withHeaders,
          secureKafka = false
        )
      }

      val ctx = setupConsumerContext
      body(ctx)
    }
  }

  def secureKafkaJsonConsumerContext[T](
      topic: String,
      keyType: Option[FormatType],
      valType: FormatType,
      numMessages: Int = 1,
      partitions: Int = 1,
      prePopulate: Boolean = true,
      useServerBasicAuth: Boolean = false,
      serverOpenIdCfg: Option[OpenIdConnectCfg] = None
  )(body: ConsumerContext => T): T =
    secureKafkaConsumerContext(
      topic = topic,
      messageType = JsonType,
      keyType = keyType,
      valType = valType,
      numMessages = numMessages,
      partitions = partitions,
      prePopulate = prePopulate,
      useServerBasicAuth = useServerBasicAuth,
      serverOpenIdCfg = serverOpenIdCfg
    )(body)

  def secureKafkaAvroConsumerContext[T](
      topic: String,
      keyType: Option[FormatType],
      valType: FormatType,
      numMessages: Int = 1,
      partitions: Int = 1,
      prePopulate: Boolean = true,
      useServerBasicAuth: Boolean = false,
      serverOpenIdCfg: Option[OpenIdConnectCfg] = None
  )(body: ConsumerContext => T): T =
    secureKafkaConsumerContext(
      topic = topic,
      messageType = AvroType,
      keyType = keyType,
      valType = valType,
      numMessages = numMessages,
      partitions = partitions,
      prePopulate = prePopulate,
      useServerBasicAuth = useServerBasicAuth,
      serverOpenIdCfg = serverOpenIdCfg
    )(body)

  // scalastyle:off
  def secureKafkaConsumerContext[T, M <: FormatType](
      topic: String,
      messageType: M,
      keyType: Option[FormatType],
      valType: FormatType,
      numMessages: Int = 1,
      partitions: Int = 1,
      prePopulate: Boolean = true,
      withHeaders: Boolean = false,
      useServerBasicAuth: Boolean = false,
      serverOpenIdCfg: Option[OpenIdConnectCfg] = None
  )(body: ConsumerContext => T): T =
    secureKafkaClusterProducerContext(
      topic = topic,
      partitions = partitions,
      useServerBasicAuth = useServerBasicAuth,
      serverOpenIdCfg = serverOpenIdCfg
    ) { implicit pctx =>
      if (prePopulate) {
        produceForMessageType(
          clientId = defaultProducerClientId,
          messageType = messageType,
          keyType = keyType,
          valType = valType,
          numMessages = numMessages,
          prePopulate = prePopulate,
          withHeaders = withHeaders,
          secureKafka = true
        )
      }
      val ctx = setupConsumerContext
      body(ctx)
    }
  // scalastyle:on

  def secureServerAvroConsumerContext[T](
      topic: String,
      keyType: Option[FormatType],
      valType: FormatType,
      numMessages: Int = 1,
      partitions: Int = 1,
      prePopulate: Boolean = true,
      withHeaders: Boolean = false,
      useServerBasicAuth: Boolean = false,
      serverOpenIdCfg: Option[OpenIdConnectCfg] = None
  )(body: ConsumerContext => T): T =
    secureServerConsumerContext(
      topic = topic,
      messageType = AvroType,
      keyType = keyType,
      valType = valType,
      numMessages = numMessages,
      partitions = partitions,
      useServerBasicAuth = useServerBasicAuth,
      serverOpenIdCfg = serverOpenIdCfg,
      prePopulate = prePopulate,
      withHeaders = withHeaders
    )(body)

  def secureServerJsonConsumerContext[T](
      topic: String,
      keyType: Option[FormatType],
      valType: FormatType,
      numMessages: Int = 1,
      partitions: Int = 1,
      prePopulate: Boolean = true,
      withHeaders: Boolean = false,
      useServerBasicAuth: Boolean = false,
      serverOpenIdCfg: Option[OpenIdConnectCfg] = None
  )(body: ConsumerContext => T): T =
    secureServerConsumerContext(
      topic = topic,
      messageType = JsonType,
      keyType = keyType,
      valType = valType,
      numMessages = numMessages,
      partitions = partitions,
      useServerBasicAuth = useServerBasicAuth,
      serverOpenIdCfg = serverOpenIdCfg,
      prePopulate = prePopulate,
      withHeaders = withHeaders
    )(body)

  // scalastyle:off
  def secureServerConsumerContext[T, M <: FormatType](
      topic: String,
      messageType: M,
      keyType: Option[FormatType],
      valType: FormatType,
      numMessages: Int = 1,
      partitions: Int = 1,
      prePopulate: Boolean = true,
      withHeaders: Boolean = false,
      useServerBasicAuth: Boolean = false,
      serverOpenIdCfg: Option[OpenIdConnectCfg] = None
  )(body: ConsumerContext => T): T = {
    // scalastyle:on
    secureServerProducerContext(
      topic = topic,
      partitions = partitions,
      useServerBasicAuth = useServerBasicAuth,
      serverOpenIdCfg = serverOpenIdCfg
    ) { implicit p =>
      if (prePopulate) {
        produceForMessageType(
          clientId = defaultProducerClientId,
          messageType = messageType,
          keyType = keyType,
          valType = valType,
          numMessages = numMessages,
          prePopulate = prePopulate,
          withHeaders = withHeaders,
          secureKafka = true
        )
      }
      val ctx = setupConsumerContext
      body(ctx)
    }
  }

  private[this] def createAvroMessagesForTypes(
      keyType: Option[FormatType],
      valType: FormatType,
      numMessages: Int,
      withHeaders: Boolean
  ): Seq[AvroProducerRecord] = {
    (keyType, valType) match {
      case (None, AvroType) =>
        createAvroProducerRecordNoneAvro(numMessages, withHeaders)

      case (Some(AvroType), AvroType) =>
        createAvroProducerRecordAvroAvro(numMessages, withHeaders)

      case (Some(LongType), StringType) =>
        createAvroProducerRecordLongString(numMessages, withHeaders)

      case (None, StringType) =>
        createAvroProducerRecordNoneString(numMessages, withHeaders)

      case (Some(StringType), ByteArrayType) =>
        createAvroProducerRecordStringBytes(numMessages, withHeaders)

      case (Some(StringType), StringType) =>
        createAvroProducerRecordStringString(numMessages, withHeaders)

      case (kt, vt) =>
        throw new NotImplementedException(
          s"Test producer messages for key/value kombo" +
            s" ${kt.getOrElse(NoType).name}/${vt.name} is not implemented."
        )
    }
  }

  private[this] def createJsonMessages(
      withKey: Boolean,
      withHeaders: Boolean,
      numMessages: Int
  ): Seq[String] = {
    if (withKey) createJsonKeyValue(numMessages, withHeaders = withHeaders)
    else createJsonValue(numMessages, withHeaders = withHeaders)
  }

  private[this] def setupConsumerContext(
      implicit pctx: ProducerContext
  ): ConsumerContext = {
    val consProbe = WSProbe()
    ConsumerContext(
      topicName = pctx.topicName,
      embeddedKafkaConfig = pctx.embeddedKafkaConfig,
      appCfg = pctx.appCfg,
      route = pctx.route,
      producerProbe = pctx.producerProbe,
      consumerProbe = consProbe
    )
  }

  // scalastyle:off
  private[this] def produceForMessageType(
      clientId: WsClientId,
      messageType: FormatType,
      keyType: Option[FormatType],
      valType: FormatType,
      numMessages: Int,
      prePopulate: Boolean,
      withHeaders: Boolean,
      secureKafka: Boolean
  )(implicit pctx: ProducerContext): Unit = {
    if (prePopulate) {
      messageType match {
        case AvroType =>
          val msgs = createAvroMessagesForTypes(
            keyType,
            valType,
            numMessages,
            withHeaders
          )
          val _ = produceAndCheckAvro(
            clientId = clientId,
            topic = pctx.topicName,
            routes = pctx.route,
            keyType = keyType,
            valType = valType,
            messages = msgs,
            kafkaCreds =
              if (secureKafka) Some(BasicHttpCredentials(kafkaUser, kafkaPass))
              else None
          )(pctx.producerProbe)

        case JsonType =>
          val messages = createJsonMessages(
            withKey = keyType.isDefined,
            withHeaders = withHeaders,
            numMessages = numMessages
          )
          val _ = produceAndCheckJson(
            clientId = clientId,
            topic = pctx.topicName,
            keyType = keyType.getOrElse(NoType),
            valType = valType,
            routes = pctx.route,
            messages = messages,
            kafkaCreds =
              if (secureKafka) Some(BasicHttpCredentials(kafkaUser, kafkaPass))
              else None
          )(pctx.producerProbe)

        case _ => fail("messageType must be one of JsonType or AvroType")
      }
    }
  }
  // scalastyle:on
}
