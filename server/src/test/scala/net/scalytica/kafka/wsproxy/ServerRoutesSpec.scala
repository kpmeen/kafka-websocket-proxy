package net.scalytica.kafka.wsproxy

import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.headers.BasicHttpCredentials
import akka.http.scaladsl.model.{ContentTypes, StatusCodes}
import akka.http.scaladsl.server._
import akka.http.scaladsl.testkit.{RouteTestTimeout, WSProbe}
import io.circe.parser._
import net.manub.embeddedkafka.Codecs._
import net.scalytica.kafka.wsproxy.SocketProtocol.{AvroPayload, JsonPayload}
import net.scalytica.kafka.wsproxy.avro.SchemaTypes.{
  AvroCommit,
  AvroConsumerRecord,
  AvroProducerRecord,
  AvroProducerResult
}
import net.scalytica.kafka.wsproxy.codecs.Decoders.brokerInfoDecoder
import net.scalytica.kafka.wsproxy.models.Formats.{
  AvroType,
  LongType,
  NoType,
  StringType
}
import net.scalytica.kafka.wsproxy.models.{
  BrokerInfo,
  ConsumerKeyValueRecord,
  ConsumerValueRecord
}
import net.scalytica.test._
import org.scalatest.Inspectors.forAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Minutes, Span}
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{EitherValues, OptionValues}

import scala.concurrent.duration._

// scalastyle:off magic.number
class ServerRoutesSpec
    extends AnyWordSpec
    with EitherValues
    with OptionValues
    with ScalaFutures
    with WSProxyKafkaSpec
    with WsProducerClientSpec
    with TestDataGenerators {

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(2, Minutes))

  implicit val timeout = RouteTestTimeout(20 seconds)

  import TestServerRoutes.{serverErrorHandler, serverRejectionHandler}

  // TODO: Refactor consumer tests to reduce boilerplate and repetitive code.

  "The server routes" should {
    "return HTTP 404 when requesting an invalid resource" in {
      val expected =
        "{\"message\":\"This is not the resource you are looking for.\"}"

      val routes = Route.seal(TestSchemaRoutes.schemaRoutes)

      Get() ~> routes ~> check {
        status mustBe NotFound
        responseAs[String] mustBe expected
      }
    }

    "return the Avro schema for producer records" in {
      val testRoutes = Route.seal(TestSchemaRoutes.schemaRoutes)

      Get("/schemas/avro/producer/record") ~> testRoutes ~> check {
        status mustBe OK
        responseAs[String] mustBe AvroProducerRecord.schema.toString(true)
      }
    }

    "return the Avro schema for producer results" in {
      val testRoutes = Route.seal(TestSchemaRoutes.schemaRoutes)

      Get("/schemas/avro/producer/result") ~> testRoutes ~> check {
        status mustBe OK
        responseAs[String] mustBe AvroProducerResult.schema.toString(true)
      }
    }

    "return the Avro schema for consumer record" in {
      val testRoutes = Route.seal(TestSchemaRoutes.schemaRoutes)

      Get("/schemas/avro/consumer/record") ~> testRoutes ~> check {
        status mustBe OK
        responseAs[String] mustBe AvroConsumerRecord.schema.toString(true)
      }
    }

    "return the Avro schema for consumer commit" in {
      val testRoutes = Route.seal(TestSchemaRoutes.schemaRoutes)

      Get("/schemas/avro/consumer/commit") ~> testRoutes ~> check {
        status mustBe OK
        responseAs[String] mustBe AvroCommit.schema.toString(true)
      }
    }

    "set up a JSON payload WebSocket producer for messages with String key" +
      " and value" in defaultProducerContext("test-topic-1") {
      case (_, _, testRoutes, wsc) =>
        implicit val wsClient = wsc

        val msgs = produceKeyValueJson(1)

        produceAndCheckJson(
          topic = "test-topic-1",
          keyType = StringType,
          valType = StringType,
          routes = Route.seal(testRoutes),
          messages = msgs
        )
    }

    "set up a JSON payload WebSocket producer for messages with String value" in
      defaultProducerContext("test-topic-2") {
        case (_, _, testRoutes, wsc) =>
          implicit val wsClient = wsc

          val msgs = produceValueJson(1)

          produceAndCheckJson(
            topic = "test-topic-2",
            keyType = NoType,
            valType = StringType,
            routes = Route.seal(testRoutes),
            messages = msgs
          )
      }

    "set up a JSON payload WebSocket producer for messages with headers and" +
      " String key and value" in defaultProducerContext("test-topic-3") {
      case (ekc, _, testRoutes, wsc) =>
        implicit val wsClient = wsc
        implicit val kcfg = ekc

        val msgs = produceKeyValueJson(1, withHeaders = true)

        produceAndCheckJson(
          topic = "test-topic-3",
          keyType = StringType,
          valType = StringType,
          routes = Route.seal(testRoutes),
          messages = msgs
        )

        // validate the topic contents
        val (k, v) =
          consumeFirstKeyedMessageFrom[String, String]("test-topic-3")
        k mustBe "foo-1"
        v mustBe "bar-1"
    }

    "set up a JSON payload WebSocket producer for messages with headers and" +
      " String values" in defaultProducerContext("test-topic-4") {
      case (ekc, _, testRoutes, wsc) =>
        implicit val wsClient = wsc
        implicit val kcfg = ekc

        val msgs = produceValueJson(1, withHeaders = true)

        produceAndCheckJson(
          topic = "test-topic-4",
          keyType = NoType,
          valType = StringType,
          routes = Route.seal(testRoutes),
          messages = msgs
        )

        // validate the topic contents
        consumeFirstMessageFrom[String]("test-topic-4") mustBe "bar-1"
    }

    "set up a JSON payload WebSocket consumer for messages with " +
      "String key and value" in
      withRunningKafkaOnFoundPort(embeddedKafkaConfig) { implicit kcfg =>
        implicit val wsCfg = appTestConfig(kcfg.kafkaPort)

        val topicName = "test-topic-5"
        initTopic(topicName)

        implicit val wsConsumerProbe = WSProbe()
        val producerProbe = WSProbe()
        val (sdcStream, testRoutes) = TestServerRoutes.wsProxyRoutes
        val ctrl = sdcStream.run()
        val routes = Route.seal(testRoutes)

        produceAndCheckJson(
          topic = topicName,
          keyType = StringType,
          valType = StringType,
          routes = routes,
          messages = produceKeyValueJson(10, withHeaders = true)
        )(producerProbe)

        val outPath = "/socket/out?" +
          "clientId=test-5" +
          "&groupId=test-group-5" +
          s"&topic=$topicName" +
          s"&keyType=${StringType.name}" +
          s"&valType=${StringType.name}" +
          "&autoCommit=false"

        import net.manub.embeddedkafka.Codecs.stringDeserializer

        // validate the topic contents
        val res = consumeNumberKeyedMessagesFrom[String, String](topicName, 10)
        res must have size 10
        forAll(res) {
          case (k, v) =>
            k must startWith("foo-")
            v must startWith("bar-")
        }
        WS(outPath, wsConsumerProbe.flow) ~> routes ~> check {
          isWebSocketUpgrade mustBe true

          forAll(1 to 10) { i =>
            wsConsumerProbe.expectWsConsumerKeyValueResultJson[String, String](
              expectedTopic = topicName,
              expectedKey = s"foo-$i",
              expectedValue = s"bar-$i"
            )
          }

        }

        ctrl.shutdown()
      }

    "set up a JSON payload WebSocket consumer for messages with String value" in
      withRunningKafkaOnFoundPort(embeddedKafkaConfig) { implicit kcfg =>
        implicit val wsCfg = appTestConfig(kcfg.kafkaPort)

        val topicName = "test-topic-6"
        initTopic(topicName)

        implicit val wsConsumerProbe = WSProbe()
        val producerProbe = WSProbe()
        val (sdcStream, testRoutes) = TestServerRoutes.wsProxyRoutes
        val ctrl = sdcStream.run()
        val routes = Route.seal(testRoutes)

        produceAndCheckJson(
          topic = topicName,
          keyType = NoType,
          valType = StringType,
          routes = routes,
          messages = produceValueJson(10)
        )(producerProbe)

        val outPath = "/socket/out?" +
          "clientId=test-6" +
          "&groupId=test-group-6" +
          s"&topic=$topicName" +
          s"&valType=${StringType.name}" +
          "&autoCommit=false"

        import net.manub.embeddedkafka.Codecs.stringDeserializer

        consumeFirstMessageFrom[String](topicName) mustBe "bar-1"

        WS(outPath, wsConsumerProbe.flow) ~> routes ~> check {
          isWebSocketUpgrade mustBe true

          forAll(1 to 10) { i =>
            wsConsumerProbe.expectWsConsumerValueResultJson[String](
              expectedTopic = topicName,
              expectedValue = s"bar-$i"
            )
          }
        }

        ctrl.shutdown()
      }

    "set up an Avro payload WebSocket producer for messages with Avro key " +
      "and value" in defaultProducerContext("test-topic-7") {
      case (_, _, testRoutes, wsc) =>
        implicit val wsClient = wsc

        val messages = produceKeyValueAvro(1)

        produceAndCheckAvro(
          topic = "test-topic-7",
          routes = Route.seal(testRoutes),
          keyType = Some(AvroType),
          valType = AvroType,
          messages = messages
        )
    }

    "set up an Avro payload WebSocket producer for messages with String key" +
      " and Avro value" in defaultProducerContext("test-topic-8") {
      case (_, _, testRoutes, wsc) =>
        implicit val wsClient = wsc

        val messages = produceKeyStringValueAvro(1)

        produceAndCheckAvro(
          topic = "test-topic-8",
          routes = Route.seal(testRoutes),
          keyType = Some(StringType),
          valType = AvroType,
          messages = messages
        )
    }

    "set up an Avro payload WebSocket producer for messages with Avro value" in
      defaultProducerContext("test-topic-9") {
        case (_, _, testRoutes, wsc) =>
          implicit val wsClient = wsc

          val messages = produceValueAvro(1)

          produceAndCheckAvro(
            topic = "test-topic-9",
            routes = Route.seal(testRoutes),
            keyType = None,
            valType = AvroType,
            messages = messages
          )
      }

    "set up an Avro payload WebSocket consumer for messages with Avro" +
      " key and value" in
      withRunningKafkaOnFoundPort(embeddedKafkaConfig) { implicit kcfg =>
        implicit val schemaRegPort = kcfg.schemaRegistryPort

        implicit val wsCfg = appTestConfig(kcfg.kafkaPort, Some(schemaRegPort))

        val topicName = "test-topic-10"
        initTopic(topicName)

        implicit val wsConsumerProbe = WSProbe()
        val producerProbe = WSProbe()
        val (sdcStream, testRoutes) = TestServerRoutes.wsProxyRoutes
        val ctrl = sdcStream.run()
        val messages = produceKeyValueAvro(10)
        val routes = Route.seal(testRoutes)

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

    "set up an Avro payload WebSocket consumer for messages with String" +
      " key and value" in
      withRunningKafkaOnFoundPort(embeddedKafkaConfig) { implicit kcfg =>
        implicit val schemaRegPort = kcfg.schemaRegistryPort

        implicit val wsCfg = appTestConfig(kcfg.kafkaPort, Some(schemaRegPort))

        val topicName = "test-topic-11"
        initTopic(topicName)

        implicit val wsConsumerProbe = WSProbe()
        val producerProbe = WSProbe()
        val (sdcStream, testRoutes) = TestServerRoutes.wsProxyRoutes
        val ctrl = sdcStream.run()
        val messages = produceKeyStringValueString(10)
        val routes = Route.seal(testRoutes)

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

    "set up an Avro payload WebSocket consumer for messages with" +
      " String value" in
      withRunningKafkaOnFoundPort(embeddedKafkaConfig) { implicit kcfg =>
        implicit val schemaRegPort = kcfg.schemaRegistryPort

        implicit val wsCfg = appTestConfig(kcfg.kafkaPort, Some(schemaRegPort))

        val topicName = "test-topic-12"
        initTopic(topicName)

        implicit val wsConsumerProbe = WSProbe()
        val producerProbe = WSProbe()
        val (sdcStream, testRoutes) = TestServerRoutes.wsProxyRoutes
        val ctrl = sdcStream.run()
        val messages = produceAvroWithStringValue(10)
        val routes = Route.seal(testRoutes)

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

    "set up an Avro payload WebSocket consumer for messages with Long key and" +
      " String value" in
      withRunningKafkaOnFoundPort(embeddedKafkaConfig) { implicit kcfg =>
        implicit val schemaRegPort = kcfg.schemaRegistryPort

        implicit val wsCfg = appTestConfig(kcfg.kafkaPort, Some(schemaRegPort))

        val topicName = "test-topic-13"
        initTopic(topicName)

        implicit val wsConsumerProbe = WSProbe()
        val producerProbe = WSProbe()
        val (sdcStream, testRoutes) = TestServerRoutes.wsProxyRoutes
        val ctrl = sdcStream.run()
        val messages = produceKeyLongValueString(10)
        val routes = Route.seal(testRoutes)

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

    "return the kafka cluster info" in
      withRunningKafkaOnFoundPort(embeddedKafkaConfig) { implicit kcfg =>
        implicit val wsCfg = appTestConfig(kcfg.kafkaPort)

        val (sdcStream, testRoutes) = TestServerRoutes.wsProxyRoutes
        val ctrl = sdcStream.run()

        Get("/kafka/cluster/info") ~> Route.seal(testRoutes) ~> check {
          status mustBe OK
          responseEntity.contentType mustBe ContentTypes.`application/json`

          val ci = parse(responseAs[String])
            .map(_.as[Seq[BrokerInfo]])
            .flatMap(identity)
            .right
            .value

          ci must have size 1
          ci.headOption.value mustBe BrokerInfo(
            id = 0,
            host = "localhost",
            port = kcfg.kafkaPort,
            rack = None
          )
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
            valType = StringType
          )

          WS(uri, wsClient.flow) ~> Route.seal(testRoutes) ~> check {
            status mustBe StatusCodes.BadRequest
          }
      }

    "return HTTP 400 when attempting to consume from non-existing topic" in
      withRunningKafkaOnFoundPort(embeddedKafkaConfig) { implicit kcfg =>
        implicit val wsCfg = appTestConfig(kcfg.kafkaPort)

        val topicName = "non-existing-topic"
        val outPath = "/socket/out?" +
          "clientId=test-100" +
          "&groupId=test-group-100" +
          s"&topic=$topicName" +
          s"&socketPayload=${AvroPayload.name}" +
          "&keyType=avro" +
          "&valType=avro" +
          "&autoCommit=false"

        implicit val wsClient = WSProbe()
        val (sdcStream, testRoutes) = TestServerRoutes.wsProxyRoutes
        val ctrl = sdcStream.run()

        WS(outPath, wsClient.flow) ~> Route.seal(testRoutes) ~> check {
          status mustBe StatusCodes.BadRequest
        }

        ctrl.shutdown()
      }

    // scalastyle:off line.size.limit
    "return a HTTP 401 when using wrong credentials to establish an outbound connection to a secured cluster" in
      // scalastyle:on line.size.limit
      secureContext { implicit kcfg =>
        implicit val wsCfg =
          secureAppTestConfig(kcfg.kafkaPort, Some(kcfg.schemaRegistryPort))

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

        implicit val wsClient = WSProbe()
        val (sdcStream, testRoutes) = TestServerRoutes.wsProxyRoutes
        val ctrl = sdcStream.run()
        val routes = Route.seal(testRoutes)

        val wrongCreds = addCredentials(BasicHttpCredentials("bad", "user"))

        WS(outPath, wsClient.flow) ~> wrongCreds ~> routes ~> check {
          status mustBe StatusCodes.Unauthorized
        }

        ctrl.shutdown()
      }

    // scalastyle:off line.size.limit
    "return a HTTP 401 when using wrong credentials to establish an inbound connection to a secured cluster" in
      // scalastyle:on line.size.limit
      secureContext { implicit kcfg =>
        implicit val wsCfg = secureAppTestConfig(kcfg.kafkaPort)

        val topicName = "restricted-topic"
        initTopic(topicName, isSecure = true)

        implicit val wsClient = WSProbe()
        val (sdcStream, testRoutes) = TestServerRoutes.wsProxyRoutes
        val ctrl = sdcStream.run()

        val baseUri = baseProducerUri(
          topic = topicName,
          payloadType = JsonPayload,
          keyType = NoType,
          valType = StringType
        )

        val wrongCreds = BasicHttpCredentials("bad", "user")

        checkWebSocket(baseUri, Route.seal(testRoutes), Some(wrongCreds)) {
          status mustBe StatusCodes.Unauthorized
        }

        ctrl.shutdown()
      }

    "set up a WebSocket for producing messages to a secured cluster" in
      secureProducerContext("secure-topic-1") {
        case (_, _, testRoutes, wsc) =>
          implicit val wsClient = wsc

          val messages = produceValueAvro(1)

          produceAndCheckAvro(
            topic = "secure-topic-1",
            routes = Route.seal(testRoutes),
            keyType = None,
            valType = AvroType,
            messages = messages,
            basicCreds = Some(creds)
          )
      }

    "set up a WebSocket for consuming messages from a secured cluster" in
      secureProducerContext("secure-topic-2") {
        case (ekc, _, testRoutes, wsProducerClient) =>
          implicit val kcfg = ekc
          val routes = Route.seal(testRoutes)
          val messages = produceValueJson(10)

          produceAndCheckJson(
            topic = "secure-topic-2",
            keyType = NoType,
            valType = StringType,
            routes = routes,
            messages = messages,
            basicCreds = Some(creds)
          )(wsProducerClient)

          import net.manub.embeddedkafka.Codecs.stringDeserializer

          val outPath = "/socket/out?" +
            "clientId=test-102" +
            "&groupId=test-group-102" +
            s"&topic=secure-topic-2" +
            "&valType=string" +
            "&autoCommit=false"

          consumeFirstMessageFrom[String]("secure-topic-2") mustBe "bar-1"

          implicit val wsConsumerClient = WSProbe()

          WS(outPath, wsConsumerClient.flow) ~>
            addCredentials(creds) ~>
            routes ~>
            check {
              isWebSocketUpgrade mustBe true

              forAll(1 to 10) { i =>
                wsConsumerClient.expectWsConsumerValueResultJson[String](
                  expectedTopic = "secure-topic-2",
                  expectedValue = s"bar-$i"
                )
              }
            }
      }

  }
}
