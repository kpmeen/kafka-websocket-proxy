package net.scalytica.test

import akka.http.scaladsl.model.headers.BasicHttpCredentials
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.{ScalatestRouteTest, WSProbe}
import com.typesafe.config.Config
import io.confluent.kafka.schemaregistry.rest.SchemaRegistryConfig._
import kafka.server.KafkaConfig._
import net.manub.embeddedkafka.ConsumerExtensions.ConsumerRetryConfig
import net.manub.embeddedkafka.schemaregistry.{
  EmbeddedKafka,
  EmbeddedKafkaConfig
}
import net.scalytica.kafka.wsproxy.Configuration.{
  AdminClientCfg,
  AppCfg,
  BasicAuthCfg,
  KafkaBootstrapHosts,
  OpenIdConnectCfg,
  SchemaRegistryCfg
}
import net.scalytica.kafka.wsproxy.models.WsServerId
import net.scalytica.kafka.wsproxy.{mapToProperties, Configuration}
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

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.util.Random

// scalastyle:off magic.number
trait WSProxyKafkaSpec
    extends FileLoader
    with ScalatestRouteTest
    with Matchers
    with EmbeddedKafka { self: Suite =>

  val testKeyPass: String         = "scalytica"
  val creds: BasicHttpCredentials = BasicHttpCredentials("client", "client")

  implicit val consumerRetryConfig: ConsumerRetryConfig =
    ConsumerRetryConfig(30, 50 millis)

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
    SASL_JAAS_CONFIG             -> """org.apache.kafka.common.security.plain.PlainLoginModule required username="client" password="client";"""
    // scalastyle:on line.size.limit
  )

  def embeddedKafkaConfigWithSasl: EmbeddedKafkaConfig = {
    val kbpPlain  = availablePort
    val kbpSecure = availablePort
    val zkp       = availablePort
    val srp       = availablePort

    val brokerSasl =
      "org.apache.kafka.common.security.plain.PlainLoginModule required " +
        "username=\"admin\" " +
        "password=\"admin\" " +
        "user_admin=\"admin\" " +
        "user_broker1=\"broker1\" " +
        "user_client=\"client\";"

    val listeners =
      s"PLAINTEXT://localhost:$kbpPlain,SASL_SSL://localhost:$kbpSecure"

    EmbeddedKafkaConfig(
      kafkaPort = kbpSecure,
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
    Configuration.loadFile(filePath("/application-test.conf"))

  lazy val defaultTestAppCfgWithServerId: String => AppCfg = (sid: String) =>
    defaultTestAppCfg.copy(
      server = defaultTestAppCfg.server.copy(serverId = WsServerId(sid))
    )

  val basicAuthUser         = "basicAuthUser"
  val basicAuthPass         = "basicAuthPass"
  val basicAuthRealm        = "Test Server"
  val basicHttpCreds        = BasicHttpCredentials(basicAuthUser, basicAuthPass)
  val invalidBasicHttpCreds = BasicHttpCredentials(basicAuthUser, "invalid")

  def basicAuthCredendials(useBasicAuth: Boolean): Option[BasicAuthCfg] = {
    if (useBasicAuth)
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

  def plainTestConfig(useBasicAuth: Boolean = false): Configuration.AppCfg = {
    val serverId = s"test-server-${Random.nextInt(50000)}"
    val basicAuthCreds =
      if (useBasicAuth)
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
      useBasicAuth: Boolean = false,
      openIdCfg: Option[OpenIdConnectCfg] = None
  ): Configuration.AppCfg = {
    val serverId = s"test-server-${Random.nextInt(50000)}"
    val srUrl =
      schemaRegistryPort.map(_ => s"http://${serverHost(schemaRegistryPort)}")
    val srCfg: Option[SchemaRegistryCfg] =
      defaultTestAppCfg.kafkaClient.schemaRegistry.fold(
        srUrl.map(u => SchemaRegistryCfg(u, autoRegisterSchemas = true))
      )(sr => Option(sr.copy(url = srUrl.getOrElse(sr.url))))

    val basicAuthCreds = basicAuthCredendials(useBasicAuth)

    defaultTestAppCfg.copy(
      server = defaultTestAppCfg.server.copy(
        serverId = WsServerId(serverId),
        basicAuth = basicAuthCreds,
        openidConnect = openIdCfg
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
      useBasicAuth: Boolean = false,
      openIdCfg: Option[OpenIdConnectCfg] = None
  ): Configuration.AppCfg = {
    val serverId = s"test-server-${Random.nextInt(50000)}"
    val srUrl =
      schemaRegistryPort.map(_ => s"http://${serverHost(schemaRegistryPort)}")
    val srCfg: Option[SchemaRegistryCfg] =
      defaultTestAppCfg.kafkaClient.schemaRegistry.fold(
        srUrl.map(u => SchemaRegistryCfg(u, autoRegisterSchemas = true))
      )(sr => Option(sr.copy(url = srUrl.getOrElse(sr.url))))

    val basicAuthCreds = basicAuthCredendials(useBasicAuth)

    defaultTestAppCfg.copy(
      server = defaultTestAppCfg.server.copy(
        serverId = WsServerId(serverId),
        basicAuth = basicAuthCreds,
        openidConnect = openIdCfg
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

  private[this] val zkSessionTimeoutMs    = 10000
  private[this] val zkConnectionTimeoutMs = 10000
  private[this] val topicCreationTimeout  = 2 seconds
//  private[this] val topicDeletionTimeout  = 2 seconds

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

  def defaultProducerContext[T](
      topic: String = "test-topic"
  )(body: (EmbeddedKafkaConfig, AppCfg, Route, WSProbe) => T): T = {
    secureServerProducerContext(topic)(body)
  }

  def secureServerProducerContext[T](
      topic: String,
      useBasicAuth: Boolean = false,
      openIdCfg: Option[OpenIdConnectCfg] = None
  )(body: (EmbeddedKafkaConfig, AppCfg, Route, WSProbe) => T): T = {
    withRunningKafkaOnFoundPort(embeddedKafkaConfig) { implicit kcfg =>
      implicit val wsCfg = appTestConfig(
        kafkaPort = kcfg.kafkaPort,
        schemaRegistryPort = Some(kcfg.schemaRegistryPort),
        useBasicAuth = useBasicAuth,
        openIdCfg = openIdCfg
      )

      initTopic(topic)

      val wsClient                = WSProbe()
      val (sdcStream, testRoutes) = TestServerRoutes.wsProxyRoutes
      val ctrl                    = sdcStream.run()

      val res = body(kcfg, wsCfg, testRoutes, wsClient)

      ctrl.shutdown()

      res
    }
  }

  def secureKafkaClusterProducerContext[T](
      topic: String = "test-topic"
  )(body: (EmbeddedKafkaConfig, AppCfg, Route, WSProbe) => T): T = {
    secureKafkaContext { implicit kcfg =>
      implicit val wsCfg =
        secureAppTestConfig(kcfg.kafkaPort, Some(kcfg.schemaRegistryPort))

      initTopic(topic, isSecure = true)

      val wsProducerClient        = WSProbe()
      val (sdcStream, testRoutes) = TestServerRoutes.wsProxyRoutes
      val ctrl                    = sdcStream.run()

      val res = body(kcfg, wsCfg, testRoutes, wsProducerClient)

      ctrl.shutdown()

      res
    }
  }

  def secureKafkaContext[T](body: EmbeddedKafkaConfig => T): T = {
    implicit val secureCfg = embeddedKafkaConfigWithSasl
    withRunningKafka(body(secureCfg))
  }
}
