package net.scalytica.test

import akka.Done
import akka.actor.typed.scaladsl.adapter._
import akka.http.scaladsl.model.headers.BasicHttpCredentials
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.{ScalatestRouteTest, WSProbe}
import com.typesafe.config.Config
import net.scalytica.kafka.wsproxy.config.{
  DynamicConfigHandler,
  DynamicConfigHandlerProtocol,
  ReadableDynamicConfigHandlerRef,
  RunnableDynamicConfigHandlerRef
}
import net.scalytica.kafka.wsproxy.logging.WithProxyLogger
import net.scalytica.kafka.wsproxy.models.{WsProducerId, WsProducerInstanceId}
import net.scalytica.kafka.wsproxy.session.SessionHandlerRef
import org.scalatest.BeforeAndAfterAll

import scala.concurrent.Future
import scala.util.{Failure, Success}
//import io.confluent.kafka.schemaregistry.rest.SchemaRegistryConfig._
import io.github.embeddedkafka.{EmbeddedKafka, EmbeddedKafkaConfig}
import jdk.jshell.spi.ExecutionControl.NotImplementedException
import kafka.server.KafkaConfig._
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
import net.scalytica.kafka.wsproxy.models.{TopicName, WsServerId}
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
    with BeforeAndAfterAll
    with Matchers
    with EmbeddedKafka
    with WithProxyLogger {
  self: Suite =>

  val testKeyPass: String         = "scalytica"
  val kafkaUser: String           = "client"
  val kafkaPass: String           = kafkaUser
  val creds: BasicHttpCredentials = BasicHttpCredentials(kafkaUser, kafkaPass)

//  implicit val routeTestTimeout = RouteTestTimeout(5 seconds)

  override protected def afterAll(): Unit = {
    super.afterAll()
    system.terminate().onComplete {
      case Success(_) =>
        log.debug(s"Actor system terminated.")
      case Failure(err) =>
        log.debug(s"Failed to shutdown actor system: $err")
    }
  }

  val embeddedKafkaConfig: EmbeddedKafkaConfig = EmbeddedKafkaConfig(
    kafkaPort = 0,
    zooKeeperPort = 0,
//    schemaRegistryPort = 0,
    customBrokerProperties = Map(
      AutoCreateTopicsEnableProp -> "false",
      ZkConnectionTimeoutMsProp  -> "60000"
    )
//    customSchemaRegistryProperties = Map(
//      KAFKASTORE_TOPIC_REPLICATION_FACTOR_CONFIG -> "1"
//    )
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
//    val srp              = availablePort

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
//      schemaRegistryPort = srp,
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

  def defaultTestAppCfgWithServerId(
      serverId: String
  ): AppCfg =
    defaultTestAppCfg.copy(
      server = defaultTestAppCfg.server.copy(serverId = WsServerId(serverId))
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
      serverOpenIdCfg: Option[OpenIdConnectCfg] = None,
      useProducerSessions: Boolean = false,
      useDynamicConfigs: Boolean = false
  ): Configuration.AppCfg = {
    if (useServerBasicAuth || serverOpenIdCfg.isDefined) {
      secureAppTestConfig(
        kafkaPort = kafkaPort,
        schemaRegistryPort = schemaRegistryPort,
        useServerBasicAuth = useServerBasicAuth,
        serverOpenIdCfg = serverOpenIdCfg,
        useProducerSessions = useProducerSessions,
        useDynamicConfigs = useDynamicConfigs
      )
    } else {
      plainAppTestConfig(
        kafkaPort = kafkaPort,
        schemaRegistryPort = schemaRegistryPort,
        useServerBasicAuth = useServerBasicAuth,
        serverOpenIdCfg = serverOpenIdCfg,
        useProducerSessions = useProducerSessions,
        useDynamicConfigs = useDynamicConfigs
      )
    }
  }

  def plainAppTestConfig(
      kafkaPort: Int,
      schemaRegistryPort: Option[Int] = None,
      useServerBasicAuth: Boolean = false,
      serverOpenIdCfg: Option[OpenIdConnectCfg] = None,
      secureHealthCheckEndpoint: Boolean = true,
      useProducerSessions: Boolean = false,
      useAdminEndpoint: Boolean = false,
      useDynamicConfigs: Boolean = false
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
        secureHealthCheckEndpoint = secureHealthCheckEndpoint,
        admin = defaultTestAppCfg.server.admin.copy(enabled = useAdminEndpoint)
      ),
      kafkaClient = defaultTestAppCfg.kafkaClient.copy(
        bootstrapHosts = KafkaBootstrapHosts(List(serverHost(Some(kafkaPort)))),
        schemaRegistry = srCfg
      ),
      producer = defaultTestAppCfg.producer.copy(
        sessionsEnabled = useProducerSessions
      ),
      dynamicConfigHandler = defaultTestAppCfg.dynamicConfigHandler.copy(
        enabled = useDynamicConfigs
      )
    )
  }

  def secureAppTestConfig(
      kafkaPort: Int,
      schemaRegistryPort: Option[Int] = None,
      useServerBasicAuth: Boolean = false,
      serverOpenIdCfg: Option[OpenIdConnectCfg] = None,
      useProducerSessions: Boolean = false,
      useAdminEndpoint: Boolean = false,
      useDynamicConfigs: Boolean = false
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
        admin = defaultTestAppCfg.server.admin.copy(enabled = useAdminEndpoint)
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
        sessionsEnabled = useProducerSessions,
        kafkaClientProperties = secureClientProps
      ),
      dynamicConfigHandler = defaultTestAppCfg.dynamicConfigHandler.copy(
        enabled = useDynamicConfigs
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
  ): Unit = {
    initialiseTopic(
      topic = topicName,
      partitions = partitions,
      isSecure = isSecure
    )
  }

  def secureKafkaContext[T](body: EmbeddedKafkaConfig => T): T = {
    implicit val cfg = embeddedKafkaConfigWithSasl
    withRunningKafka(body(cfg))
  }

  def plainAdminServerContext[T](
      useDynamicConfigs: Boolean = true
  )(
      body: (
          EmbeddedKafkaConfig,
          AppCfg,
          SessionHandlerRef,
          Option[RunnableDynamicConfigHandlerRef],
          Option[OpenIdClient]
      ) => T
  ): T = {
    withRunningKafkaOnFoundPort(embeddedKafkaConfig) { implicit kcfg =>
      implicit val wsCfg = plainAppTestConfig(
        kafkaPort = kcfg.kafkaPort,
        useAdminEndpoint = true,
        useDynamicConfigs = useDynamicConfigs
      )
      implicit val maybeOidClient: Option[OpenIdClient] = None
      implicit val sessionHandlerRef                    = SessionHandler.init
      implicit val maybeDynCfgHandlerRef =
        if (useDynamicConfigs) Option(DynamicConfigHandler.init) else None

      val sessionCtrl = sessionHandlerRef.stream.run()
      val maybeCtrl   = maybeDynCfgHandlerRef.map(_.stream.run())

      val res = body(
        kcfg,
        wsCfg,
        sessionHandlerRef,
        maybeDynCfgHandlerRef,
        maybeOidClient
      )

      maybeCtrl.foreach(_.stop())
      sessionCtrl.stop()

      res
    }
  }

  def plainContextNoWebSockets[T](
      useProducerSessions: Boolean = false,
      useDynamicConfigs: Boolean = false
  )(
      body: (EmbeddedKafkaConfig, AppCfg) => T
  ): T = {
    withRunningKafkaOnFoundPort(embeddedKafkaConfig) { implicit kcfg =>
      val wsCfg = plainAppTestConfig(
        kafkaPort = kcfg.kafkaPort,
        useProducerSessions = useProducerSessions,
        useDynamicConfigs = useDynamicConfigs
      )
      body(kcfg, wsCfg)
    }
  }

  // scalastyle:off method.length
  def plainServerContext[T](
      useProducerSessions: Boolean = false,
      useDynamicConfigs: Boolean = false
  )(
      body: (
          EmbeddedKafkaConfig,
          AppCfg,
          SessionHandlerRef,
          Option[ReadableDynamicConfigHandlerRef],
          Option[OpenIdClient]
      ) => T
  ): T = {
    withRunningKafkaOnFoundPort(embeddedKafkaConfig) { implicit kcfg =>
      implicit val wsCfg = plainAppTestConfig(
        kafkaPort = kcfg.kafkaPort,
        useProducerSessions = useProducerSessions,
        useDynamicConfigs = useDynamicConfigs
      )
      implicit val oidClient: Option[OpenIdClient] = None
      implicit val sessionHandlerRef               = SessionHandler.init
      implicit val optRunnableDynCfgHandler =
        if (useDynamicConfigs) Option(DynamicConfigHandler.init) else None

      implicit val optReadableDynCfgHandler =
        optRunnableDynCfgHandler.map(_.asReadOnlyRef)

      val sessionCtrl = sessionHandlerRef.stream.run()
      val dynCfgCtrl  = optRunnableDynCfgHandler.map(_.stream.run())

      val res = body(
        kcfg,
        wsCfg,
        sessionHandlerRef,
        optReadableDynCfgHandler,
        oidClient
      )

      sessionHandlerRef.shRef.tell(
        SessionHandlerProtocol.StopSessionHandler(system.toTyped.ignoreRef)
      )

      optRunnableDynCfgHandler.foreach(
        _.dchRef.tell(
          DynamicConfigHandlerProtocol.StopConfigHandler(
            system.toTyped.ignoreRef
          )
        )
      )

      sessionCtrl.drainAndShutdown(Future.successful(Done))
      dynCfgCtrl.map(_.drainAndShutdown(Future.successful(Done)))

      res
    }
  }
  // scalastyle:on method.length

  private[this] def secureAdminServerContext[T](
      useServerBasicAuth: Boolean,
      useDynamicConfigs: Boolean,
      serverOpenIdCfg: Option[OpenIdConnectCfg] = None
  )(
      body: (
          EmbeddedKafkaConfig,
          AppCfg,
          SessionHandlerRef,
          Option[RunnableDynamicConfigHandlerRef],
          Option[OpenIdClient]
      ) => T
  ): T = {
    withRunningKafkaOnFoundPort(embeddedKafkaConfig) { implicit kcfg =>
      implicit val wsCfg = plainAppTestConfig(
        kafkaPort = kcfg.kafkaPort,
        useServerBasicAuth = useServerBasicAuth,
        serverOpenIdCfg = serverOpenIdCfg,
        useAdminEndpoint = true,
        useDynamicConfigs = useDynamicConfigs
      )
      implicit val oidClient = wsCfg.server.openidConnect
        .filter(_.enabled)
        .map(_ => OpenIdClient(wsCfg))
      implicit val sessionHandlerRef = SessionHandler.init
      implicit val optRunnableDynCfgHandler =
        if (useDynamicConfigs) Option(DynamicConfigHandler.init) else None

      val sessionCtrl = sessionHandlerRef.stream.run()
      val maybeCtrl   = optRunnableDynCfgHandler.map(_.stream.run())

      val res = body(
        kcfg,
        wsCfg,
        sessionHandlerRef,
        optRunnableDynCfgHandler,
        oidClient
      )

      maybeCtrl.foreach(_.stop())
      sessionCtrl.stop()

      res
    }
  }

  def openIdAdminServerContext[T](
      openIdCfg: OpenIdConnectCfg,
      useDynamicConfigs: Boolean = true
  )(
      body: (
          EmbeddedKafkaConfig,
          AppCfg,
          SessionHandlerRef,
          Option[RunnableDynamicConfigHandlerRef],
          Option[OpenIdClient]
      ) => T
  ): T = {
    secureAdminServerContext(
      useServerBasicAuth = false,
      useDynamicConfigs = useDynamicConfigs,
      serverOpenIdCfg = Some(openIdCfg)
    )(body)
  }

  def basicAuthAdminServerContext[T](
      useDynamicConfigs: Boolean = true
  )(
      body: (
          EmbeddedKafkaConfig,
          AppCfg,
          SessionHandlerRef,
          Option[RunnableDynamicConfigHandlerRef],
          Option[OpenIdClient]
      ) => T
  ): T = {
    secureAdminServerContext(
      useServerBasicAuth = true,
      useDynamicConfigs = useDynamicConfigs
    )(body)
  }

  def secureContextNoWebSockets[T](
      useServerBasicAuth: Boolean = false,
      serverOpenIdCfg: Option[OpenIdConnectCfg] = None,
      secureHealthCheckEndpoint: Boolean = true,
      useProducerSessions: Boolean = false,
      useDynamicConfigs: Boolean = false
  )(
      body: (EmbeddedKafkaConfig, AppCfg) => T
  ): T = {
    withRunningKafkaOnFoundPort(embeddedKafkaConfig) { implicit kcfg =>
      val wsCfg = plainAppTestConfig(
        kafkaPort = kcfg.kafkaPort,
        //        schemaRegistryPort = Some(kcfg.schemaRegistryPort),
        useServerBasicAuth = useServerBasicAuth,
        serverOpenIdCfg = serverOpenIdCfg,
        secureHealthCheckEndpoint = secureHealthCheckEndpoint,
        useProducerSessions = useProducerSessions,
        useDynamicConfigs = useDynamicConfigs
      )

      body(kcfg, wsCfg)
    }
  }

  // scalastyle:off method.length
  def secureServerContext[T](
      useServerBasicAuth: Boolean = false,
      serverOpenIdCfg: Option[OpenIdConnectCfg] = None,
      secureHealthCheckEndpoint: Boolean = true,
      useProducerSessions: Boolean = false,
      useDynamicConfigs: Boolean = false
  )(
      body: (
          EmbeddedKafkaConfig,
          AppCfg,
          SessionHandlerRef,
          Option[ReadableDynamicConfigHandlerRef],
          Option[OpenIdClient]
      ) => T
  ): T = {
    withRunningKafkaOnFoundPort(embeddedKafkaConfig) { implicit kcfg =>
      implicit val wsCfg = plainAppTestConfig(
        kafkaPort = kcfg.kafkaPort,
        //        schemaRegistryPort = Some(kcfg.schemaRegistryPort),
        useServerBasicAuth = useServerBasicAuth,
        serverOpenIdCfg = serverOpenIdCfg,
        secureHealthCheckEndpoint = secureHealthCheckEndpoint,
        useProducerSessions = useProducerSessions,
        useDynamicConfigs = useDynamicConfigs
      )

      implicit val oidClient = wsCfg.server.openidConnect
        .filter(_.enabled)
        .map(_ => OpenIdClient(wsCfg))
      implicit val sessionHandlerRef = SessionHandler.init
      implicit val optRunnableDynCfgHandler =
        if (useDynamicConfigs) Option(DynamicConfigHandler.init) else None
      implicit val optReadableDynCfgHandler =
        optRunnableDynCfgHandler.map(_.asReadOnlyRef)

      val sessionCtrl = sessionHandlerRef.stream.run()
      val dynCfgCtrl  = optRunnableDynCfgHandler.map(_.stream.run())

      val res = body(
        kcfg,
        wsCfg,
        sessionHandlerRef,
        optReadableDynCfgHandler,
        oidClient
      )

      sessionHandlerRef.shRef.tell(
        SessionHandlerProtocol.StopSessionHandler(system.toTyped.ignoreRef)
      )

      optRunnableDynCfgHandler.foreach(
        _.dchRef.tell(
          DynamicConfigHandlerProtocol.StopConfigHandler(
            system.toTyped.ignoreRef
          )
        )
      )

      sessionCtrl.drainAndShutdown(Future.successful(Done))
      dynCfgCtrl.map(_.drainAndShutdown(Future.successful(Done)))

      res
    }
  }
  // scalastyle:on method.length

}

trait WsProxyProducerKafkaSpec
    extends WsProxyKafkaSpec
    with WsProducerClientSpec { self: Suite =>

  case class ProducerContext(
      topicName: TopicName,
      embeddedKafkaConfig: EmbeddedKafkaConfig,
      appCfg: AppCfg,
      sessionHandlerRef: SessionHandlerRef,
      optReadDynCfgHandlerRef: Option[ReadableDynamicConfigHandlerRef],
      optOidClient: Option[OpenIdClient],
      producerProbe: WSProbe
  )

  def wsRouteFromProducerContext(implicit ctx: ProducerContext): Route = {
    wsRouteFrom(
      ctx.appCfg,
      ctx.sessionHandlerRef,
      ctx.optReadDynCfgHandlerRef,
      ctx.optOidClient
    )
  }

  def plainProducerContext[T](
      topic: String = "test-topic",
      partitions: Int = 1,
      useProducerSessions: Boolean = false,
      useDynamicConfigs: Boolean = false
  )(body: ProducerContext => T): T =
    plainServerContext(useProducerSessions, useDynamicConfigs) {
      (kcfg, appCfg, sessionHandler, optReadCfgHandler, optOidClient) =>
        initTopic(topic, partitions)(kcfg)

        val ctx = ProducerContext(
          topicName = TopicName(topic),
          embeddedKafkaConfig = kcfg,
          appCfg = appCfg,
          sessionHandlerRef = sessionHandler,
          optReadDynCfgHandlerRef = optReadCfgHandler,
          optOidClient = optOidClient,
          producerProbe = WSProbe()
        )

        body(ctx)
    }

  def secureServerProducerContext[T](
      topic: String,
      partitions: Int = 1,
      useServerBasicAuth: Boolean = false,
      serverOpenIdCfg: Option[OpenIdConnectCfg] = None,
      useProducerSessions: Boolean = false,
      useDynamicConfigs: Boolean = false
  )(body: ProducerContext => T): T = {
    secureKafkaClusterProducerContext(
      topic = topic,
      partitions = partitions,
      useServerBasicAuth = useServerBasicAuth,
      serverOpenIdCfg = serverOpenIdCfg,
      useProducerSessions = useProducerSessions,
      useDynamicConfigs = useDynamicConfigs
    )(body)
  }

  // scalastyle:off method.length
  def secureKafkaClusterProducerContext[T](
      topic: String = "test-topic",
      partitions: Int = 1,
      useServerBasicAuth: Boolean = false,
      serverOpenIdCfg: Option[OpenIdConnectCfg] = None,
      useProducerSessions: Boolean = false,
      useDynamicConfigs: Boolean = false
  )(body: ProducerContext => T): T = {
    secureKafkaContext { implicit kcfg =>
      implicit val wsCfg =
        secureAppTestConfig(
          kafkaPort = kcfg.kafkaPort,
//          schemaRegistryPort = Some(kcfg.schemaRegistryPort),
          useServerBasicAuth = useServerBasicAuth,
          serverOpenIdCfg = serverOpenIdCfg,
          useProducerSessions = useProducerSessions,
          useDynamicConfigs = useDynamicConfigs
        )

      implicit val optOidClient = wsCfg.server.openidConnect
        .filter(_.enabled)
        .map(_ => OpenIdClient(wsCfg))
      implicit val sessionHandlerRef = SessionHandler.init
      implicit val optRunnableDynCfgHandler =
        if (useDynamicConfigs) Option(DynamicConfigHandler.init) else None
      implicit val optReadableDynCfgHandler =
        optRunnableDynCfgHandler.map(_.asReadOnlyRef)

      initTopic(topic, partitions, isSecure = true)

      val sessionCtrl = sessionHandlerRef.stream.run()
      val dynCfgCtrl  = optRunnableDynCfgHandler.map(_.stream.run())

      val producerProbe = WSProbe()

      val ctx = ProducerContext(
        topicName = TopicName(topic),
        embeddedKafkaConfig = kcfg,
        appCfg = wsCfg,
        sessionHandlerRef = sessionHandlerRef,
        optReadDynCfgHandlerRef = optReadableDynCfgHandler,
        optOidClient = optOidClient,
        producerProbe = producerProbe
      )
      val res = body(ctx)

      sessionHandlerRef.shRef.tell(
        SessionHandlerProtocol.StopSessionHandler(system.toTyped.ignoreRef)
      )

      optRunnableDynCfgHandler.foreach(
        _.dchRef.tell(
          DynamicConfigHandlerProtocol.StopConfigHandler(
            system.toTyped.ignoreRef
          )
        )
      )

      sessionCtrl.drainAndShutdown(Future.successful(Done))
      dynCfgCtrl.map(_.drainAndShutdown(Future.successful(Done)))

      res
    }
  }
  // scalastyle:on method.length

}

trait WsProxyConsumerKafkaSpec extends WsProxyProducerKafkaSpec { self: Suite =>

  private[this] val defaultProducerClientId =
    WsProducerId("test-producer-client")

  case class ConsumerContext(
      topicName: TopicName,
      embeddedKafkaConfig: EmbeddedKafkaConfig,
      appCfg: AppCfg,
      sessionHandlerRef: SessionHandlerRef,
      optReadDynCfgHandlerRef: Option[ReadableDynamicConfigHandlerRef],
      optOidClient: Option[OpenIdClient],
      producerProbe: WSProbe,
      consumerProbe: WSProbe
  )

  def wsRouteFromConsumerContext(implicit ctx: ConsumerContext): Route = {
    wsRouteFrom(
      ctx.appCfg,
      ctx.sessionHandlerRef,
      ctx.optReadDynCfgHandlerRef,
      ctx.optOidClient
    )
  }

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
          producerId = defaultProducerClientId,
          instanceId = None,
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
      useDynamicConfigs: Boolean = false,
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
      useDynamicConfigs = useDynamicConfigs,
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
      useDynamicConfigs: Boolean = false,
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
      useDynamicConfigs = useDynamicConfigs,
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
      useDynamicConfigs: Boolean = false,
      serverOpenIdCfg: Option[OpenIdConnectCfg] = None
  )(body: ConsumerContext => T): T =
    secureKafkaClusterProducerContext(
      topic = topic,
      partitions = partitions,
      useServerBasicAuth = useServerBasicAuth,
      useDynamicConfigs = useDynamicConfigs,
      serverOpenIdCfg = serverOpenIdCfg
    ) { implicit pctx =>
      if (prePopulate) {
        produceForMessageType(
          producerId = defaultProducerClientId,
          instanceId = None,
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

  // scalastyle:off parameter.number
  def secureServerAvroConsumerContext[T](
      topic: String,
      keyType: Option[FormatType],
      valType: FormatType,
      numMessages: Int = 1,
      partitions: Int = 1,
      prePopulate: Boolean = true,
      withHeaders: Boolean = false,
      useServerBasicAuth: Boolean = false,
      useDynamicConfigs: Boolean = false,
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
      useDynamicConfigs = useDynamicConfigs,
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
      useDynamicConfigs: Boolean = false,
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
      useDynamicConfigs = useDynamicConfigs,
      serverOpenIdCfg = serverOpenIdCfg,
      prePopulate = prePopulate,
      withHeaders = withHeaders
    )(body)

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
      useDynamicConfigs: Boolean = false,
      serverOpenIdCfg: Option[OpenIdConnectCfg] = None
  )(body: ConsumerContext => T): T = {
    // scalastyle:on
    secureServerProducerContext(
      topic = topic,
      partitions = partitions,
      useServerBasicAuth = useServerBasicAuth,
      useDynamicConfigs = useDynamicConfigs,
      serverOpenIdCfg = serverOpenIdCfg
    ) { implicit p =>
      if (prePopulate) {
        produceForMessageType(
          producerId = defaultProducerClientId,
          instanceId = None,
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
  // scalastyle:on parameter.number

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
      sessionHandlerRef = pctx.sessionHandlerRef,
      optReadDynCfgHandlerRef = pctx.optReadDynCfgHandlerRef,
      optOidClient = pctx.optOidClient,
      producerProbe = pctx.producerProbe,
      consumerProbe = consProbe
    )
  }

  // scalastyle:off method.length
  private[this] def produceForMessageType(
      producerId: WsProducerId,
      instanceId: Option[WsProducerInstanceId],
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
          val _ = produceAndAssertAvro(
            producerId = producerId,
            instanceId = instanceId,
            topic = pctx.topicName,
            routes = wsRouteFromProducerContext,
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
          val _ = produceAndAssertJson(
            producerId = producerId,
            instanceId = instanceId,
            topic = pctx.topicName,
            keyType = keyType.getOrElse(NoType),
            valType = valType,
            routes = wsRouteFromProducerContext,
            messages = messages,
            kafkaCreds =
              if (secureKafka) Some(BasicHttpCredentials(kafkaUser, kafkaPass))
              else None
          )(pctx.producerProbe)

        case _ => fail("messageType must be one of JsonType or AvroType")
      }
    }
  }
  // scalastyle:on method.length
}
