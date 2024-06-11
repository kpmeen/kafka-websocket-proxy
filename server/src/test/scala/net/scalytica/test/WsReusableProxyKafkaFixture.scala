package net.scalytica.test

import io.github.embeddedkafka.{EmbeddedK, EmbeddedKafka, EmbeddedKafkaConfig}
import net.scalytica.kafka.wsproxy.admin.WsKafkaAdminClient
import net.scalytica.kafka.wsproxy.mapToProperties
import net.scalytica.kafka.wsproxy.auth.{AccessToken, OpenIdClient}
import net.scalytica.kafka.wsproxy.codecs.BasicSerdes.StringDeserializer
import net.scalytica.kafka.wsproxy.config.Configuration.{
  AppCfg,
  CustomJwtCfg,
  OpenIdConnectCfg
}
import net.scalytica.kafka.wsproxy.config.{
  DynamicConfigHandler,
  ReadableDynamicConfigHandlerRef,
  RunnableDynamicConfigHandlerRef
}
import net.scalytica.kafka.wsproxy.models.Formats.{
  FormatType,
  JsonType,
  StringType
}
import net.scalytica.kafka.wsproxy.models.{
  ReadIsolationLevel,
  TopicName,
  WsClientId,
  WsGroupId
}
import net.scalytica.kafka.wsproxy.session.{SessionHandler, SessionHandlerRef}
import net.scalytica.test.SharedAttributes._
import org.apache.kafka.clients.admin.AdminClientConfig.{
  BOOTSTRAP_SERVERS_CONFIG,
  CLIENT_ID_CONFIG,
  CONNECTIONS_MAX_IDLE_MS_CONFIG,
  REQUEST_TIMEOUT_MS_CONFIG,
  RETRIES_CONFIG,
  RETRY_BACKOFF_MS_CONFIG,
  SECURITY_PROTOCOL_CONFIG
}
import org.apache.kafka.clients.admin.{AdminClient, NewTopic}
import org.apache.kafka.clients.consumer.ConsumerConfig.{
  AUTO_OFFSET_RESET_CONFIG,
  ENABLE_AUTO_COMMIT_CONFIG,
  GROUP_ID_CONFIG
}
import org.apache.kafka.clients.consumer.{
  ConsumerRecord,
  KafkaConsumer,
  OffsetResetStrategy
}
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.common.serialization.{Deserializer, Serializer}
import org.apache.pekko.Done
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.adapter._
import org.apache.pekko.http.scaladsl.testkit.WSProbe
import org.apache.pekko.kafka.scaladsl.Consumer

import java.time.Duration
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

// scalastyle:off magic.number
trait WsReusableProxyKafkaFixture {
  self: WsProxySpec =>

  protected var kafkaContext: ReusableKafkaContext = _

  protected lazy val secureKafka: Boolean = false

  override val zkSessionTimeoutMs: Int              = 10000
  override val zkConnectionTimeoutMs: Int           = 10000
  override val topicCreationTimeout: FiniteDuration = 5 seconds

  override def beforeAll(): Unit = {
    kafkaContext = new ReusableKafkaContext
    val kcfg =
      if (secureKafka) embeddedKafkaConfigWithSasl
      else embeddedKafkaConfig

    kafkaContext.start(kcfg)
  }

  override def afterAll(): Unit = {
    kafkaContext.stop()
  }

  def kafkaConsumer[K, V](
      groupId: WsGroupId,
      clientId: WsClientId
  )(
      implicit keyDes: Deserializer[K],
      valDes: Deserializer[V]
  ): KafkaConsumer[K, V] = {
    val ekcfg = kafkaContext.kafkaConfig
    val consCfg = Map(
      GROUP_ID_CONFIG           -> groupId.value,
      CLIENT_ID_CONFIG          -> clientId.value,
      BOOTSTRAP_SERVERS_CONFIG  -> s"localhost:${ekcfg.kafkaPort}",
      ENABLE_AUTO_COMMIT_CONFIG -> false.toString,
      AUTO_OFFSET_RESET_CONFIG ->
        OffsetResetStrategy.EARLIEST.toString.toLowerCase
    )
    new KafkaConsumer[K, V](consCfg, keyDes, valDes)
  }

  def kafkaProducer[K, V]()(
      implicit keySer: Serializer[K],
      valSer: Serializer[V]
  ): KafkaProducer[K, V] = {
    val ekcfg = kafkaContext.kafkaConfig
    val prodCfg = Map(
      BOOTSTRAP_SERVERS_CONFIG  -> s"localhost:${ekcfg.kafkaPort}",
      SECURITY_PROTOCOL_CONFIG  -> "PLAINTEXT",
      REQUEST_TIMEOUT_MS_CONFIG -> "20000",
      RETRIES_CONFIG            -> "2147483647",
      RETRY_BACKOFF_MS_CONFIG   -> "500"
    )

    new KafkaProducer[K, V](prodCfg, keySer, valSer)
  }

  def prepConsumer[K, V](
      gid: WsGroupId,
      cid: WsClientId,
      topic: TopicName,
      active: Boolean
  )(
      implicit keyDes: Deserializer[K],
      valDes: Deserializer[V]
  ): KafkaConsumer[K, V] = {
    val c = kafkaConsumer[K, V](gid, cid)
    c.subscribe(List(topic.value).asJavaCollection)
    var recs = Iterable.empty[ConsumerRecord[K, V]]

    while ({
      recs = c.poll(Duration.ofMillis(100L)).records(topic.value).asScala
      recs.isEmpty
    }) {
      log.trace("Found no records, will try to poll again...")
    }

    c.commitSync()
    if (!active) c.close()
    c
  }

  def prepareConsumerGroups[T](
      topicName: TopicName,
      grpNamePrefix: String,
      numActiveClients: Int = 1,
      numInactiveClients: Int = 0
  )(
      body: (
          Seq[KafkaConsumer[String, String]],
          Seq[KafkaConsumer[String, String]]
      ) => T
  ): T = {
    val activeConsumers = (1 to numActiveClients).map { idx =>
      prepConsumer[String, String](
        gid = WsGroupId(s"$grpNamePrefix-active-$idx"),
        cid = WsClientId(s"client-active-$idx"),
        topic = topicName,
        active = true
      )
    }
    val inactiveConsumers = (1 to numInactiveClients).map { idx =>
      prepConsumer[String, String](
        gid = WsGroupId(s"$grpNamePrefix-inactive-$idx"),
        cid = WsClientId(s"client-inactive-$idx"),
        topic = topicName,
        active = false
      )
    }
    try {
      body(activeConsumers, inactiveConsumers)
    } finally {
      activeConsumers.foreach(_.close())
    }
  }

  def withNoContext[T](
      useServerBasicAuth: Boolean = false,
      serverOpenIdCfg: Option[OpenIdConnectCfg] = None,
      secureHealthCheckEndpoint: Boolean = true,
      useProducerSessions: Boolean = false,
      useDynamicConfigs: Boolean = false,
      useFreshStateTopics: Boolean = false
  )(
      body: (EmbeddedKafkaConfig, AppCfg) => T
  ): T = {
    val wsCfg = plainAppTestConfig(
      kafkaPort = kafkaContext.kafkaConfig.kafkaPort,
      useServerBasicAuth = useServerBasicAuth,
      serverOpenIdCfg = serverOpenIdCfg,
      secureHealthCheckEndpoint = secureHealthCheckEndpoint,
      useProducerSessions = useProducerSessions,
      useDynamicConfigs = useDynamicConfigs,
      useFreshStateTopics = useFreshStateTopics
    )

    body(kafkaContext.kafkaConfig, wsCfg)
  }

  def withWsKafkaAdminClient[T](
      body: WsKafkaAdminClient => T
  )(implicit appCfg: AppCfg): T = {
    val admin = new WsKafkaAdminClient(appCfg)
    val res   = body(admin)
    admin.close()
    res
  }

  def withAdminContext[T](
      useServerBasicAuth: Boolean = false,
      useDynamicConfigs: Boolean = false,
      optOpenIdCfg: Option[OpenIdConnectCfg] = None,
      useFreshStateTopics: Boolean = false
  )(
      body: (
          EmbeddedKafkaConfig,
          AppCfg,
          SessionHandlerRef,
          Option[RunnableDynamicConfigHandlerRef],
          WsKafkaAdminClient
      ) => T
  ): T = {
    val proxyContext = new WsProxyContext(
      useBasicAuth = useServerBasicAuth,
      useDynamicConfigs = useDynamicConfigs,
      maybeOpenIdCfg = optOpenIdCfg,
      useAdminEndpoint = true,
      useFreshStateTopics = useFreshStateTopics
    )

    proxyContext.start(kafkaContext.kafkaConfig)

    withWsKafkaAdminClient { adminClient =>
      val res = body(
        kafkaContext.kafkaConfig,
        proxyContext.getAppCfg,
        proxyContext.getSessionHandler,
        proxyContext.maybeRunnableConfigHandler,
        adminClient
      )

      proxyContext.stop()

      res
    }(proxyContext.getAppCfg)
  }

  def withProducerContext[T](
      topic: String,
      partitions: Int = 1,
      useProducerSessions: Boolean = false,
      useDynamicConfigs: Boolean = false,
      useExactlyOnce: Boolean = false,
      useBasicAuth: Boolean = false
  )(body: ProducerContext => T): T = {
    // initialise
    val proxyContext = new WsProxyContext(
      useBasicAuth = useBasicAuth,
      useProducerSessions = useProducerSessions,
      useDynamicConfigs = useDynamicConfigs,
      useExactlyOnce = useExactlyOnce
    )
    proxyContext.start(kafkaContext.kafkaConfig)
    kafkaContext.createTopics(Map(topic -> partitions))
    // run test
    val res = body(
      kafkaContext.createProducerContext(Some(topic), proxyContext)
    )
    // tear-down
    proxyContext.stop()
    kafkaContext.deleteTopic(topic)
    // finished
    res
  }

  def withConsumerContext[T](
      topic: String,
      keyType: Option[FormatType] = Some(StringType),
      valType: FormatType = StringType,
      numMessages: Int = 1,
      partitions: Int = 1,
      prePopulate: Boolean = true,
      withHeaders: Boolean = false,
      useBasicAuth: Boolean = false
  )(body: ConsumerContext => T): T = {
    withProducerContext(
      topic,
      partitions = partitions,
      useBasicAuth = useBasicAuth
    ) { implicit pctx =>
      if (prePopulate) {
        produceForMessageType(
          producerId = defaultProducerClientId,
          instanceId = None,
          messageType = JsonType,
          keyType = keyType,
          valType = valType,
          numMessages = numMessages,
          prePopulate = prePopulate,
          withHeaders = withHeaders,
          secureKafka = secureKafka
        )
      }
      val cctx = kafkaContext.createConsumerContext(pctx)
      body(cctx)
    }
  }

  def withOpenIdServerProducerContext[T](
      topic: String,
      partitions: Int = 1,
      useJwtCreds: Boolean = false,
      customJwtCfg: Option[CustomJwtCfg] = None,
      tokenValidationInterval: FiniteDuration = 10 minutes,
      errorLimit: Int = -1,
      useProducerSessions: Boolean = false,
      useDynamicConfigs: Boolean = false,
      useExactlyOnce: Boolean = false
  )(body: (OpenIdConnectCfg, OpenIdClient, ProducerContext) => T): T = {
    withOpenIdConnectServer(
      useJwtCreds = useJwtCreds,
      validationInterval = tokenValidationInterval,
      errorLimit = errorLimit
    ) { case (_, _, oidcCfg) =>
      // initialise
      val openIdConnectCfg = customJwtCfg
        .map(_ => oidcCfg.copy(customJwt = customJwtCfg))
        .getOrElse(oidcCfg)
      val openIdClient = OpenIdClient(
        oidcCfg = openIdConnectCfg,
        enforceHttps = false
      )
      val proxyContext = new WsProxyContext(
        useBasicAuth = false,
        maybeOpenIdCfg = Option(openIdConnectCfg),
        useProducerSessions = useProducerSessions,
        useDynamicConfigs = useDynamicConfigs,
        useExactlyOnce = useExactlyOnce
      )
      proxyContext.start(kafkaContext.kafkaConfig)
      kafkaContext.createTopics(Map(topic -> partitions))
      // run test
      val res = body(
        openIdConnectCfg,
        openIdClient,
        kafkaContext.createProducerContext(Some(topic), proxyContext)
      )
      // tear-down
      proxyContext.stop()
      kafkaContext.deleteTopic(topic)
      // finished
      res
    }
  }

  def withOpenIdServerConsumerContext[T](
      topic: String,
      partitions: Int = 1,
      useJwtCreds: Boolean = false,
      tokenValidationInterval: FiniteDuration = 10 minutes,
      keyType: Option[FormatType] = Some(StringType),
      valType: FormatType = StringType,
      numMessages: Int = 1,
      prePopulate: Boolean = true,
      withHeaders: Boolean = false
  )(body: (OpenIdConnectCfg, OpenIdClient, ConsumerContext) => T): T = {
    withOpenIdServerProducerContext(
      topic,
      partitions = partitions,
      useJwtCreds = useJwtCreds,
      tokenValidationInterval = tokenValidationInterval
    ) { case (oidcCfg, oidcClient, pctx) =>
      if (prePopulate) {
        produceForMessageType(
          producerId = defaultProducerClientId,
          instanceId = None,
          messageType = JsonType,
          keyType = keyType,
          valType = valType,
          numMessages = numMessages,
          prePopulate = prePopulate,
          withHeaders = withHeaders,
          secureKafka = secureKafka
        )(pctx)
      }
      val cctx = kafkaContext.createConsumerContext(pctx)
      body(oidcCfg, oidcClient, cctx)
    }
  }

  def withUnavailableOpenIdProducerContext[T](
      topic: String,
      partitions: Int = 1,
      useProducerSessions: Boolean = false,
      useDynamicConfigs: Boolean = false,
      useExactlyOnce: Boolean = false
  )(body: (OpenIdClient, AccessToken, ProducerContext) => T): T = {
    withUnavailableOpenIdConnectServerAndToken(
      useJwtCreds = false
    ) { case (oidcClient, oidcCfg, oidcToken) =>
      // initialise
      val proxyContext = new WsProxyContext(
        useBasicAuth = false,
        maybeOpenIdCfg = Option(oidcCfg),
        useProducerSessions = useProducerSessions,
        useDynamicConfigs = useDynamicConfigs,
        useExactlyOnce = useExactlyOnce
      )
      proxyContext.start(kafkaContext.kafkaConfig)
      kafkaContext.createTopics(Map(topic -> partitions))
      // run test
      val res = body(
        oidcClient,
        oidcToken,
        kafkaContext.createProducerContext(Some(topic), proxyContext)
      )
      // tear-down
      proxyContext.stop()
      kafkaContext.deleteTopic(topic)
      // finished
      res
    }
  }

  class WsProxyContext(
      useBasicAuth: Boolean = false,
      maybeOpenIdCfg: Option[OpenIdConnectCfg] = None,
      useAdminEndpoint: Boolean = false,
      useProducerSessions: Boolean = false,
      useDynamicConfigs: Boolean = false,
      useExactlyOnce: Boolean = false,
      useFreshStateTopics: Boolean = false
  ) {
    private[this] var running: Boolean = false

    private[this] var appCfg: AppCfg                     = _
    private[this] var shRef: SessionHandlerRef           = _
    private[this] var optOidClient: Option[OpenIdClient] = None
    private[this] var optCfgHandler: Option[RunnableDynamicConfigHandlerRef] =
      None
    private[this] var optReadCfgHandler
        : Option[ReadableDynamicConfigHandlerRef] = _

    private[this] var sessionCtrl: Consumer.Control        = _
    private[this] var dynCfgCtrl: Option[Consumer.Control] = None

    def getAppCfg: AppCfg                       = appCfg
    def getSessionHandler: SessionHandlerRef    = shRef
    def maybeOpenIdClient: Option[OpenIdClient] = optOidClient
    def maybeRunnableConfigHandler: Option[RunnableDynamicConfigHandlerRef] =
      optCfgHandler
    def maybeReadableConfigHandler: Option[ReadableDynamicConfigHandlerRef] =
      optReadCfgHandler

    def isRunning: Boolean = running

    private[this] def prepareAppCfg(ekcfg: EmbeddedKafkaConfig): AppCfg = {
      if (secureKafka) {
        secureAppTestConfig(
          kafkaPort = ekcfg.kafkaPort,
          useServerBasicAuth = useBasicAuth,
          serverOpenIdCfg = maybeOpenIdCfg,
          useProducerSessions = useProducerSessions,
          useAdminEndpoint = useAdminEndpoint,
          useDynamicConfigs = useDynamicConfigs,
          useExactlyOnce = useExactlyOnce,
          useFreshStateTopics = useFreshStateTopics
        )
      } else {
        plainAppTestConfig(
          kafkaPort = ekcfg.kafkaPort,
          useServerBasicAuth = useBasicAuth,
          serverOpenIdCfg = maybeOpenIdCfg,
          useProducerSessions = useProducerSessions,
          useAdminEndpoint = useAdminEndpoint,
          useDynamicConfigs = useDynamicConfigs,
          useExactlyOnce = useExactlyOnce,
          useFreshStateTopics = useFreshStateTopics
        )
      }
    }

    def start(ekcfg: EmbeddedKafkaConfig): Unit = {
      appCfg = prepareAppCfg(ekcfg)

      optOidClient =
        maybeOpenIdCfg.filter(_.enabled).map(_ => OpenIdClient(appCfg))

      shRef = SessionHandler.init(appCfg, system)
      if (useDynamicConfigs) {
        optCfgHandler = Option(DynamicConfigHandler.init(appCfg, system))
      }
      optReadCfgHandler = optCfgHandler.map(_.asReadOnlyRef)

      sessionCtrl = shRef.stream.run()
      dynCfgCtrl = optCfgHandler.map(_.stream.run())

      running = true
    }

    def stop(): Unit = {
      implicit val typedSystem: ActorSystem[_] = system.toTyped
      shRef.stop()
      optCfgHandler.foreach(_.stop())
      optReadCfgHandler.foreach(_.stop())

      val _ = sessionCtrl.drainAndShutdown(Future.successful(Done))
      val _ = dynCfgCtrl.map(_.drainAndShutdown(Future.successful(Done)))

      running = false
    }

  }

  class ReusableKafkaContext {
    private[this] var kafka: EmbeddedK = _

    private[this] val topicTimeout: FiniteDuration = 10 seconds

    def stop(): Unit = EmbeddedKafka.stop()

    def isRunning: Boolean = EmbeddedKafka.isRunning

    def start(implicit ekcfg: EmbeddedKafkaConfig): Unit =
      kafka = EmbeddedKafka.start()

    def setupAdminClient(
        isSecure: Boolean
    )(implicit config: EmbeddedKafkaConfig): AdminClient = {
      val baseProps = Map[String, String](
        BOOTSTRAP_SERVERS_CONFIG       -> s"localhost:${config.kafkaPort}",
        CLIENT_ID_CONFIG               -> "embedded-kafka-admin-client",
        REQUEST_TIMEOUT_MS_CONFIG      -> zkSessionTimeoutMs.toString,
        CONNECTIONS_MAX_IDLE_MS_CONFIG -> zkConnectionTimeoutMs.toString
      )

      val adminProps: java.util.Properties =
        if (isSecure) baseProps ++ secureClientProps else baseProps

      AdminClient.create(adminProps)
    }

    def createTopics(topics: Map[String, Int]): Unit = {
      if (isRunning) {
        val adminClient = setupAdminClient(secureKafka)(kafkaConfig)
        val newTopics = topics.map { case (topicName, partitions) =>
          new NewTopic(topicName, partitions, 1.toShort)
            .configs(Map.empty[String, String].asJava)
        }.asJavaCollection

        try {
          val _ = adminClient
            .createTopics(newTopics)
            .all()
            .get(topicTimeout.length, topicTimeout.unit)
        } catch {
          case je: java.util.concurrent.TimeoutException =>
            fail(
              s"Timed out attempting to create" +
                s"${if (secureKafka) " secure" else ""}" +
                s" topics ${topics.mkString(", ")}",
              je
            )
        } finally {
          adminClient.close()
        }
      } else {
        fail(
          s"Cannot create topics [${topics.keys.mkString(", ")}]." +
            " because Kafka is not running."
        )
      }
    }

    def listAllTopicNames: Set[String] = {
      val adminClient: AdminClient = setupAdminClient(secureKafka)(kafkaConfig)
      adminClient.listTopics().names().get().asScala.toSet
    }

    def deleteAllTopics(): Unit = {
      implicit val adminClient: AdminClient =
        setupAdminClient(secureKafka)(kafkaConfig)
      val existingNames = adminClient.listTopics().names().get()
      deleteTopics(existingNames.asScala.toSet)
    }

    def deleteTopic(topic: String): Unit = {
      implicit val adminClient: AdminClient =
        setupAdminClient(secureKafka)(kafkaConfig)
      deleteTopics(Set(topic))
    }

    def deleteTopics(
        topics: Set[String]
    )(implicit adminClient: AdminClient): Unit = {
      try {
        if (topics.nonEmpty) {
          val _ = adminClient
            .deleteTopics(topics.asJavaCollection)
            .all()
            .get(topicTimeout.length, topicTimeout.unit)
        }
      } catch {
        case je: java.util.concurrent.TimeoutException =>
          fail(
            s"Timed out attempting to delete" +
              s"${if (secureKafka) " secure" else ""}" +
              s" topics ${topics.mkString(", ")}",
            je
          )
      } finally {
        adminClient.close()
      }
    }

    def kafkaConfig: EmbeddedKafkaConfig = {
      kafka.config
    }

    def configWithReadIsolationLevel(
        readIsolationLevel: ReadIsolationLevel
    ): EmbeddedKafkaConfig = {
      kafka.config.withConsumerReadIsolation(readIsolationLevel)
    }

    def createConsumerContext(pctx: ProducerContext): ConsumerContext = {
      setupConsumerContext(pctx)
    }

    def createProducerContext(
        topic: Option[String],
        proxyContext: WsProxyContext
    ): ProducerContext = {
      ProducerContext(
        topicName = topic.map(TopicName.apply),
        embeddedKafkaConfig = kafkaConfig,
        appCfg = proxyContext.getAppCfg,
        sessionHandlerRef = proxyContext.getSessionHandler,
        optReadDynCfgHandlerRef = proxyContext.maybeReadableConfigHandler,
        optOidClient = proxyContext.maybeOpenIdClient,
        producerProbe = WSProbe()
      )
    }

  }

}
