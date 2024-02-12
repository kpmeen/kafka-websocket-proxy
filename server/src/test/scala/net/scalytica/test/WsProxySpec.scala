package net.scalytica.test

import com.typesafe.config.Config
import org.apache.pekko.http.scaladsl.model.headers.BasicHttpCredentials
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.testkit.{ScalatestRouteTest, WSProbe}
import org.scalatest.BeforeAndAfterAll

import scala.util.{Failure, Success}
import io.github.embeddedkafka.{EmbeddedKafka, EmbeddedKafkaConfig}
import net.scalytica.kafka.wsproxy.config._
import net.scalytica.kafka.wsproxy.logging.WithProxyLogger
import net.scalytica.kafka.wsproxy.models.{WsProducerId, WsProducerInstanceId}
import net.scalytica.kafka.wsproxy.session.SessionHandlerRef
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
import net.scalytica.kafka.wsproxy.models.Formats._
import net.scalytica.kafka.wsproxy.models.{TopicName, WsServerId}
import net.scalytica.test.TestDataGenerators._
import net.scalytica.test.SharedAttributes._
import org.scalatest.Suite
import org.scalatest.matchers.must.Matchers

import scala.util.Random

// scalastyle:off magic.number
trait WsProxySpec
    extends FileLoader
    with ScalatestRouteTest
    with BeforeAndAfterAll
    with Matchers
    with EmbeddedKafka
    with WsProducerClientSpec
    with MockOpenIdServer
    with WithProxyLogger {
  self: Suite =>

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

  override def testConfig: Config = defaultTypesafeConfig

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
      useDynamicConfigs: Boolean = false,
      useExactlyOnce: Boolean = false
  ): Configuration.AppCfg = {
    if (useServerBasicAuth || serverOpenIdCfg.isDefined) {
      secureAppTestConfig(
        kafkaPort = kafkaPort,
        schemaRegistryPort = schemaRegistryPort,
        useServerBasicAuth = useServerBasicAuth,
        serverOpenIdCfg = serverOpenIdCfg,
        useProducerSessions = useProducerSessions,
        useDynamicConfigs = useDynamicConfigs,
        useExactlyOnce = useExactlyOnce
      )
    } else {
      plainAppTestConfig(
        kafkaPort = kafkaPort,
        schemaRegistryPort = schemaRegistryPort,
        useServerBasicAuth = useServerBasicAuth,
        serverOpenIdCfg = serverOpenIdCfg,
        useProducerSessions = useProducerSessions,
        useDynamicConfigs = useDynamicConfigs,
        useExactlyOnce = useExactlyOnce
      )
    }
  }

  // scalastyle:off method.length
  def plainAppTestConfig(
      kafkaPort: Int,
      schemaRegistryPort: Option[Int] = None,
      useServerBasicAuth: Boolean = false,
      serverOpenIdCfg: Option[OpenIdConnectCfg] = None,
      secureHealthCheckEndpoint: Boolean = true,
      useProducerSessions: Boolean = false,
      useAdminEndpoint: Boolean = false,
      useDynamicConfigs: Boolean = false,
      useExactlyOnce: Boolean = false,
      useFreshStateTopics: Boolean = false
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
        sessionsEnabled = useProducerSessions,
        exactlyOnceEnabled = useExactlyOnce
      ),
      dynamicConfigHandler = defaultTestAppCfg.dynamicConfigHandler.copy(
        enabled = useDynamicConfigs,
        topicName =
          if (useFreshStateTopics)
            TopicName(s"${serverId}_wsproxy.dynamic.configs")
          else defaultTestAppCfg.dynamicConfigHandler.topicName
      ),
      sessionHandler = defaultTestAppCfg.sessionHandler.copy(
        topicName =
          if (useFreshStateTopics)
            TopicName(s"${serverId}_wsproxy.session.state")
          else defaultTestAppCfg.sessionHandler.topicName
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
      useDynamicConfigs: Boolean = false,
      useExactlyOnce: Boolean = false,
      useFreshStateTopics: Boolean = false
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
        exactlyOnceEnabled = useExactlyOnce,
        kafkaClientProperties = secureClientProps
      ),
      dynamicConfigHandler = defaultTestAppCfg.dynamicConfigHandler.copy(
        enabled = useDynamicConfigs,
        topicName =
          if (useFreshStateTopics)
            TopicName(s"${serverId}_wsproxy.dynamic.configs")
          else defaultTestAppCfg.dynamicConfigHandler.topicName
      ),
      sessionHandler = defaultTestAppCfg.sessionHandler.copy(
        topicName =
          if (useFreshStateTopics)
            TopicName(s"${serverId}_wsproxy.session.state")
          else defaultTestAppCfg.sessionHandler.topicName
      )
    )
  }
  // scalastyle:on method.length

  case class ProducerContext(
      topicName: Option[TopicName],
      embeddedKafkaConfig: EmbeddedKafkaConfig,
      appCfg: AppCfg,
      sessionHandlerRef: SessionHandlerRef,
      optReadDynCfgHandlerRef: Option[ReadableDynamicConfigHandlerRef],
      optOidClient: Option[OpenIdClient],
      producerProbe: WSProbe
  )

  case class ConsumerContext(
      topicName: Option[TopicName],
      embeddedKafkaConfig: EmbeddedKafkaConfig,
      appCfg: AppCfg,
      sessionHandlerRef: SessionHandlerRef,
      optReadDynCfgHandlerRef: Option[ReadableDynamicConfigHandlerRef],
      optOidClient: Option[OpenIdClient],
      producerProbe: WSProbe,
      consumerProbe: WSProbe
  )

  def setupConsumerContext(
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

  def wsRouteFromProducerContext(implicit ctx: ProducerContext): Route = {
    wsRouteFrom(
      ctx.appCfg,
      ctx.sessionHandlerRef,
      ctx.optReadDynCfgHandlerRef,
      ctx.optOidClient
    )
  }

  def wsRouteFromConsumerContext(implicit ctx: ConsumerContext): Route = {
    wsRouteFrom(
      ctx.appCfg,
      ctx.sessionHandlerRef,
      ctx.optReadDynCfgHandlerRef,
      ctx.optOidClient
    )
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
        throw new NotImplementedError(
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

  // scalastyle:off method.length
  protected def produceForMessageType(
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
    pctx.topicName
      .map { topicName =>
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
                topic = topicName,
                routes = wsRouteFromProducerContext,
                keyType = keyType,
                valType = valType,
                messages = msgs,
                kafkaCreds =
                  if (secureKafka)
                    Some(BasicHttpCredentials(kafkaUser, kafkaPass))
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
                topic = topicName,
                keyType = keyType.getOrElse(NoType),
                valType = valType,
                routes = wsRouteFromProducerContext,
                messages = messages,
                kafkaCreds =
                  if (secureKafka)
                    Some(BasicHttpCredentials(kafkaUser, kafkaPass))
                  else None
              )(pctx.producerProbe)

            case _ => fail("messageType must be one of JsonType or AvroType")
          }
        }
      }
      .getOrElse {
        failTest("ProducerContext does not contain a TopicName.")
      }
  }
  // scalastyle:on method.length
}
