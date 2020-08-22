package net.scalytica.kafka.wsproxy

import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.headers.BasicHttpCredentials
import akka.http.scaladsl.server._
import akka.http.scaladsl.testkit.{RouteTestTimeout, WSProbe}
import net.manub.embeddedkafka.Codecs._
import net.scalytica.kafka.wsproxy.SocketProtocol.AvroPayload
import net.scalytica.kafka.wsproxy.auth.{AccessToken, OpenIdClient}
import net.scalytica.kafka.wsproxy.models.Formats.{
  AvroType,
  LongType,
  NoType,
  StringType
}
import net.scalytica.kafka.wsproxy.models.{
  ConsumerKeyValueRecord,
  ConsumerValueRecord
}
import net.scalytica.kafka.wsproxy.session.SessionHandler
import net.scalytica.test._
import org.scalatest.Inspectors.forAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Minutes, Span}
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{EitherValues, OptionValues}

import scala.concurrent.duration._

// scalastyle:off magic.number
class WebSocketRoutesAvroSpec
    extends AnyWordSpec
    with EitherValues
    with OptionValues
    with ScalaFutures
    with WSProxyKafkaSpec
    with WsProducerClientSpec
    with MockOpenIdServer
    with TestDataGenerators {

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(2, Minutes))

  implicit val timeout = RouteTestTimeout(20 seconds)

  import TestServerRoutes.{serverErrorHandler, serverRejectionHandler}

  "Using Avro payloads with WebSockets" when {

    "the server routes" should {

      "produce messages with Avro key and value" in
        defaultProducerContext("test-topic-7") {
          case (_, _, testRoutes, wsc) =>
            implicit val wsClient = wsc

            val messages = createAvroProducerRecordKeyValue(1)

            produceAndCheckAvro(
              topic = "test-topic-7",
              routes = Route.seal(testRoutes),
              keyType = Some(AvroType),
              valType = AvroType,
              messages = messages
            )
        }

      "produce messages with String key and Avro value" in
        defaultProducerContext("test-topic-8") {
          case (_, _, testRoutes, wsc) =>
            implicit val wsClient = wsc

            val messages = createAvroProducerRecordStringBytes(1)

            produceAndCheckAvro(
              topic = "test-topic-8",
              routes = Route.seal(testRoutes),
              keyType = Some(StringType),
              valType = AvroType,
              messages = messages
            )
        }

      "produce messages with Avro value" in
        defaultProducerContext("test-topic-9") {
          case (_, _, testRoutes, wsc) =>
            implicit val wsClient = wsc

            val messages = createAvroProducerRecordAvroAvro(1)

            produceAndCheckAvro(
              topic = "test-topic-9",
              routes = Route.seal(testRoutes),
              keyType = None,
              valType = AvroType,
              messages = messages
            )
        }

      "consume messages with Avro key and value" in
        withRunningKafkaOnFoundPort(embeddedKafkaConfig) { implicit kcfg =>
          implicit val schemaRegPort = kcfg.schemaRegistryPort

          implicit val wsCfg =
            appTestConfig(kcfg.kafkaPort, Some(schemaRegPort))

          implicit val oidClient: Option[OpenIdClient] = None
          implicit val sessionHandlerRef               = SessionHandler.init

          val topicName = "test-topic-10"
          initTopic(topicName)

          implicit val wsConsumerProbe = WSProbe()
          val producerProbe            = WSProbe()
          val (sdcStream, testRoutes)  = TestServerRoutes.wsProxyRoutes
          val ctrl                     = sdcStream.run()
          val messages                 = createAvroProducerRecordKeyValue(10)
          val routes                   = Route.seal(testRoutes)

          produceAndCheckAvro(
            topic = topicName,
            routes = routes,
            keyType = Some(AvroType),
            valType = AvroType,
            messages = messages
          )(producerProbe)

          val outPath = "/socket/out?" +
            "clientId=test-10" +
            "&groupId=test-group-10" +
            s"&topic=$topicName" +
            s"&socketPayload=${AvroPayload.name}" +
            s"&keyType=${AvroType.name}" +
            s"&valType=${AvroType.name}" +
            "&autoCommit=false"

          implicit val keySerdes = TestSerdes.keySerdes.deserializer()
          implicit val valSerdes = TestSerdes.valueSerdes.deserializer()

          val (rk, rv) = consumeFirstKeyedMessageFrom[TestKey, Album](topicName)
          rk.username mustBe "foo-1"
          rv.artist mustBe "artist-1"
          rv.title mustBe "title-1"
          rv.tracks must have size 3
          forAll(rv.tracks) { t =>
            t.name must startWith("track-")
            t.duration mustBe (120 seconds).toMillis
          }

          WS(outPath, wsConsumerProbe.flow) ~> routes ~> check {
            isWebSocketUpgrade mustBe true

            forAll(1 to 10) { i =>
              val expectedTracks = (1 to 3).map { i =>
                TestTypes.Track(s"track-$i", (120 seconds).toMillis)
              }
              wsConsumerProbe.expectWsConsumerKeyValueResultAvro(
                expectedTopic = topicName,
                expectedKey = Option(TestTypes.TestKey(s"foo-$i", 1234567L)),
                expectedValue =
                  TestTypes.Album(s"artist-$i", s"title-$i", expectedTracks)
              )
            }

          }

          ctrl.shutdown()
        }

      "consume messages with String key and value" in
        withRunningKafkaOnFoundPort(embeddedKafkaConfig) { implicit kcfg =>
          implicit val schemaRegPort = kcfg.schemaRegistryPort

          implicit val wsCfg =
            appTestConfig(kcfg.kafkaPort, Some(schemaRegPort))

          implicit val oidClient: Option[OpenIdClient] = None
          implicit val sessionHandlerRef               = SessionHandler.init

          val topicName = "test-topic-11"
          initTopic(topicName)

          implicit val wsConsumerProbe = WSProbe()
          val producerProbe            = WSProbe()
          val (sdcStream, testRoutes)  = TestServerRoutes.wsProxyRoutes
          val ctrl                     = sdcStream.run()
          val routes                   = Route.seal(testRoutes)

          val messages = createAvroProducerRecordStringString(10)

          produceAndCheckAvro(
            topic = topicName,
            routes = routes,
            keyType = Some(StringType),
            valType = StringType,
            messages = messages
          )(producerProbe)

          val outPath = "/socket/out?" +
            "clientId=test-11" +
            "&groupId=test-group-11" +
            s"&topic=$topicName" +
            s"&socketPayload=${AvroPayload.name}" +
            s"&keyType=${StringType.name}" +
            s"&valType=${StringType.name}" +
            "&autoCommit=false"

          val (rk, rv) = consumeFirstKeyedMessageFrom[String, String](topicName)
          rk mustBe "foo-1"
          rv mustBe "artist-1"

          WS(outPath, wsConsumerProbe.flow) ~> routes ~> check {
            isWebSocketUpgrade mustBe true

            forAll(1 to 10) { i =>
              wsConsumerProbe.expectWsConsumerResultAvro[String, String](
                expectedTopic = topicName,
                keyFormat = StringType,
                valFormat = StringType
              ) {
                case ConsumerKeyValueRecord(_, _, _, _, _, key, value, _) =>
                  key.value mustBe s"foo-$i"
                  value.value mustBe s"artist-$i"

                case _ =>
                  fail("Unexpected ConsumerValueRecord")
              }
            }
          }

          ctrl.shutdown()
        }

      "consume messages with String value" in
        withRunningKafkaOnFoundPort(embeddedKafkaConfig) { implicit kcfg =>
          implicit val schemaRegPort = kcfg.schemaRegistryPort

          implicit val wsCfg =
            appTestConfig(kcfg.kafkaPort, Some(schemaRegPort))

          implicit val oidClient: Option[OpenIdClient] = None
          implicit val sessionHandlerRef               = SessionHandler.init

          val topicName = "test-topic-12"
          initTopic(topicName)

          implicit val wsConsumerProbe = WSProbe()
          val producerProbe            = WSProbe()
          val (sdcStream, testRoutes)  = TestServerRoutes.wsProxyRoutes
          val ctrl                     = sdcStream.run()
          val messages                 = createAvroProducerRecordNoneString(10)
          val routes                   = Route.seal(testRoutes)

          produceAndCheckAvro(
            topic = topicName,
            routes = routes,
            keyType = None,
            valType = StringType,
            messages = messages
          )(producerProbe)

          val outPath = "/socket/out?" +
            "clientId=test-12" +
            "&groupId=test-group-12" +
            s"&topic=$topicName" +
            s"&socketPayload=${AvroPayload.name}" +
            s"&valType=${StringType.name}" +
            "&autoCommit=false"

          val rv = consumeFirstMessageFrom[String](topicName)
          rv mustBe "artist-1"

          WS(outPath, wsConsumerProbe.flow) ~> routes ~> check {
            isWebSocketUpgrade mustBe true

            forAll(1 to 10) { i =>
              wsConsumerProbe.expectWsConsumerResultAvro[String, String](
                expectedTopic = topicName,
                keyFormat = NoType,
                valFormat = StringType
              ) {
                case ConsumerValueRecord(_, _, _, _, _, value, _) =>
                  value.value mustBe s"artist-$i"

                case _ =>
                  fail("Unexpected ConsumerKeyValueRecord.")
              }
            }
          }

          ctrl.shutdown()
        }

      "consume message with Long key and String value" in
        withRunningKafkaOnFoundPort(embeddedKafkaConfig) { implicit kcfg =>
          implicit val schemaRegPort = kcfg.schemaRegistryPort

          implicit val wsCfg =
            appTestConfig(kcfg.kafkaPort, Some(schemaRegPort))

          implicit val oidClient: Option[OpenIdClient] = None
          implicit val sessionHandlerRef               = SessionHandler.init

          val topicName = "test-topic-13"
          initTopic(topicName)

          implicit val wsConsumerProbe = WSProbe()
          val producerProbe            = WSProbe()
          val (sdcStream, testRoutes)  = TestServerRoutes.wsProxyRoutes
          val ctrl                     = sdcStream.run()
          val messages                 = createAvroProducerRecordLongString(10)
          val routes                   = Route.seal(testRoutes)

          produceAndCheckAvro(
            topic = topicName,
            routes = routes,
            keyType = Some(LongType),
            valType = StringType,
            messages = messages
          )(producerProbe)

          val outPath = "/socket/out?" +
            "clientId=test-13" +
            "&groupId=test-group-13" +
            s"&topic=$topicName" +
            s"&socketPayload=${AvroPayload.name}" +
            s"&keyType=${LongType.name}" +
            s"&valType=${StringType.name}" +
            "&autoCommit=false"

          implicit val longDeserializer = TestSerdes.longSerdes.deserializer()

          val (rk, rv) = consumeFirstKeyedMessageFrom[Long, String](topicName)
          rk mustBe 1L
          rv mustBe "artist-1"

          WS(outPath, wsConsumerProbe.flow) ~> routes ~> check {
            isWebSocketUpgrade mustBe true

            forAll(1 to 10) { i =>
              wsConsumerProbe.expectWsConsumerResultAvro[Long, String](
                expectedTopic = topicName,
                keyFormat = LongType,
                valFormat = StringType
              ) {
                case ConsumerKeyValueRecord(_, _, _, _, _, key, value, _) =>
                  key.value mustBe i.toLong
                  value.value mustBe s"artist-$i"

                case _ =>
                  fail("Unexpected ConsumerValueRecord")
              }
            }
          }

          ctrl.shutdown()
        }

      "return HTTP 400 when attempting to produce to a non-existing topic" in
        defaultProducerContext() {
          case (_, _, testRoutes, wsc) =>
            implicit val wsClient = wsc

            val topicName = "non-existing-topic"

            val uri = baseProducerUri(
              topicName,
              keyType = StringType,
              valType = StringType,
              payloadType = AvroPayload
            )

            WS(uri, wsClient.flow) ~> Route.seal(testRoutes) ~> check {
              status mustBe BadRequest
            }
        }

      "return HTTP 400 when attempting to consume from non-existing topic" in
        withRunningKafkaOnFoundPort(embeddedKafkaConfig) { implicit kcfg =>
          implicit val wsCfg = appTestConfig(kcfg.kafkaPort)

          implicit val oidClient: Option[OpenIdClient] = None
          implicit val sessionHandlerRef               = SessionHandler.init

          val topicName = "non-existing-topic"
          val outPath = "/socket/out?" +
            "clientId=test-100" +
            "&groupId=test-group-100" +
            s"&topic=$topicName" +
            s"&socketPayload=${AvroPayload.name}" +
            "&keyType=avro" +
            "&valType=avro" +
            "&autoCommit=false"

          implicit val wsClient       = WSProbe()
          val (sdcStream, testRoutes) = TestServerRoutes.wsProxyRoutes
          val ctrl                    = sdcStream.run()

          WS(outPath, wsClient.flow) ~> Route.seal(testRoutes) ~> check {
            status mustBe BadRequest
          }

          ctrl.shutdown()
        }

      // scalastyle:off line.size.limit
      "return a HTTP 401 when using wrong credentials to establish an outbound connection to a secured cluster" in
        // scalastyle:on line.size.limit
        secureKafkaContext { implicit kcfg =>
          implicit val wsCfg =
            secureAppTestConfig(kcfg.kafkaPort, Some(kcfg.schemaRegistryPort))
          implicit val oidClient: Option[OpenIdClient] = None
          implicit val sessionHandlerRef               = SessionHandler.init

          val topicName = "restricted-topic"
          initTopic(topicName, isSecure = true)

          val outPath = "/socket/out?" +
            "clientId=test-101" +
            "&groupId=test-group-101" +
            s"&topic=$topicName" +
            s"&socketPayload=${AvroPayload.name}" +
            "&keyType=avro" +
            "&valType=avro" +
            "&autoCommit=false"

          implicit val wsClient       = WSProbe()
          val (sdcStream, testRoutes) = TestServerRoutes.wsProxyRoutes
          val ctrl                    = sdcStream.run()
          val routes                  = Route.seal(testRoutes)

          val wrongCreds = addKafkaCreds(BasicHttpCredentials("bad", "user"))

          WS(outPath, wsClient.flow) ~> wrongCreds ~> routes ~> check {
            status mustBe Unauthorized
          }

          ctrl.shutdown()
        }

      // scalastyle:off line.size.limit
      "return a HTTP 401 when using wrong credentials to establish an inbound connection to a secured cluster" in
        // scalastyle:on line.size.limit
        secureKafkaContext { implicit kcfg =>
          implicit val wsCfg = secureAppTestConfig(kcfg.kafkaPort)

          implicit val oidClient: Option[OpenIdClient] = None
          implicit val sessionHandlerRef               = SessionHandler.init

          val topicName = "restricted-topic"
          initTopic(topicName, isSecure = true)

          implicit val wsClient       = WSProbe()
          val (sdcStream, testRoutes) = TestServerRoutes.wsProxyRoutes
          val ctrl                    = sdcStream.run()

          val baseUri = baseProducerUri(
            topic = topicName,
            payloadType = AvroPayload,
            keyType = NoType,
            valType = StringType
          )

          val wrongCreds = BasicHttpCredentials("bad", "user")

          checkWebSocket(
            uri = baseUri,
            routes = Route.seal(testRoutes),
            kafkaCreds = Some(wrongCreds)
          ) {
            status mustBe Unauthorized
          }

          ctrl.shutdown()
        }

      "produce messages to a secured cluster" in
        secureKafkaClusterProducerContext("secure-topic-1") {
          case (_, _, testRoutes, wsc) =>
            implicit val wsClient = wsc

            val messages = createAvroProducerRecordAvroAvro(1)

            produceAndCheckAvro(
              topic = "secure-topic-1",
              routes = Route.seal(testRoutes),
              keyType = None,
              valType = AvroType,
              messages = messages,
              kafkaCreds = Some(creds)
            )
        }

      "allow connections with a valid bearer token when OpenID is enabled" in
        withEmbeddedOpenIdConnectServerAndToken() {
          case (_, _, _, cfg, token) =>
            secureServerProducerContext("openid-1", openIdCfg = Option(cfg)) {
              case (_, _, testRoutes, wsc) =>
                implicit val wsClient = wsc

                val messages = createAvroProducerRecordAvroAvro(1)

                produceAndCheckAvro(
                  topic = "openid-1",
                  routes = Route.seal(testRoutes),
                  keyType = None,
                  valType = AvroType,
                  messages = messages,
                  creds = Some(token.bearerToken)
                )
            }
        }

      "return HTTP 401 when bearer token is invalid with OpenID enabled" in
        withEmbeddedOpenIdConnectServerAndClient() {
          case (_, _, _, cfg) =>
            secureServerProducerContext("openid-2", openIdCfg = Option(cfg)) {
              case (_, _, testRoutes, wsc) =>
                implicit val wsClient = wsc

                val token = AccessToken("Bearer", "foo.bar.baz", 3600L, None)

                val baseUri = baseProducerUri(
                  topic = "openid-2",
                  payloadType = AvroPayload,
                  keyType = NoType,
                  valType = StringType
                )

                checkWebSocket(
                  uri = baseUri,
                  routes = Route.seal(testRoutes),
                  creds = Some(token.bearerToken)
                ) {
                  status mustBe Unauthorized
                }
            }
        }
    }
  }
}
