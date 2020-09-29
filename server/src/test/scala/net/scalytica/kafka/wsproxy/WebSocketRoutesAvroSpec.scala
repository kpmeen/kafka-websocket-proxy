package net.scalytica.kafka.wsproxy

import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.headers.BasicHttpCredentials
import akka.http.scaladsl.server._
import akka.http.scaladsl.testkit.RouteTestTimeout
import net.manub.embeddedkafka.Codecs._
import net.scalytica.kafka.wsproxy.SocketProtocol.AvroPayload
import net.scalytica.kafka.wsproxy.auth.AccessToken
import net.scalytica.kafka.wsproxy.models.Formats.{
  AvroType,
  LongType,
  NoType,
  StringType
}
import net.scalytica.kafka.wsproxy.models.{
  ConsumerKeyValueRecord,
  ConsumerValueRecord,
  TopicName
}
import net.scalytica.test._
import org.scalatest.Inspectors.forAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Minutes, Span}
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{Assertion, EitherValues, OptionValues}

import scala.concurrent.duration._

// scalastyle:off magic.number
class WebSocketRoutesAvroSpec
    extends AnyWordSpec
    with EitherValues
    with OptionValues
    with ScalaFutures
    with WsProxyConsumerKafkaSpec
    with MockOpenIdServer
    with TestDataGenerators {

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(2, Minutes))

  implicit val timeout = RouteTestTimeout(20 seconds)

  implicit val testKeyDeserializer = TestSerdes.keySerdes.deserializer()
  implicit val albumDeserializer   = TestSerdes.valueSerdes.deserializer()
  implicit val longDeserializer    = TestSerdes.longSerdes.deserializer()

  import TestServerRoutes.{serverErrorHandler, serverRejectionHandler}

  private[this] def verifyTestKeyAndAlbum(
      testKey: TestKey,
      album: Album,
      expectedIndex: Int = 1
  ): Assertion = {
    testKey.username mustBe s"foo-$expectedIndex"
    album.artist mustBe s"artist-$expectedIndex"
    album.title mustBe s"title-$expectedIndex"
    album.tracks must have size 3
    forAll(album.tracks) { t =>
      t.name must startWith("track-")
      t.duration mustBe (120 seconds).toMillis
    }
  }

  "Using Avro payloads with WebSockets" when {

    "the server routes" should {

      "produce messages with Avro key and value" in
        plainProducerContext("test-topic-7") { ctx =>
          implicit val wsClient = ctx.producerProbe

          val messages = createAvroProducerRecordAvroAvro(1)

          produceAndCheckAvro(
            topic = ctx.topicName,
            routes = Route.seal(ctx.route),
            keyType = Some(AvroType),
            valType = AvroType,
            messages = messages
          )
        }

      "produce messages with String key and Avro value" in
        plainProducerContext("test-topic-8") { ctx =>
          implicit val wsClient = ctx.producerProbe

          val messages = createAvroProducerRecordStringBytes(1)

          produceAndCheckAvro(
            topic = ctx.topicName,
            routes = Route.seal(ctx.route),
            keyType = Some(StringType),
            valType = AvroType,
            messages = messages
          )
        }

      "produce messages with Avro value" in
        plainProducerContext("test-topic-9") { ctx =>
          implicit val wsClient = ctx.producerProbe

          val messages = createAvroProducerRecordNoneAvro(1)

          produceAndCheckAvro(
            topic = ctx.topicName,
            routes = Route.seal(ctx.route),
            keyType = None,
            valType = AvroType,
            messages = messages
          )
        }

      "consume messages with Avro key and value" in
        plainAvroConsumerContext(
          topic = "test-topic-10",
          keyType = Some(AvroType),
          valType = AvroType,
          numMessages = 10
        ) { ctx =>
          implicit val kcfg = ctx.embeddedKafkaConfig

          val outPath = "/socket/out?" +
            "clientId=test-10" +
            "&groupId=test-group-10" +
            s"&topic=${ctx.topicName.value}" +
            s"&socketPayload=${AvroPayload.name}" +
            s"&keyType=${AvroType.name}" +
            s"&valType=${AvroType.name}" +
            "&autoCommit=false"

          val (rk, rv) =
            consumeFirstKeyedMessageFrom[TestKey, Album](ctx.topicName.value)

          verifyTestKeyAndAlbum(rk, rv)

          WS(outPath, ctx.consumerProbe.flow) ~> ctx.route ~> check {
            isWebSocketUpgrade mustBe true

            forAll(1 to 10) { i =>
              val expectedTracks = (1 to 3).map { i =>
                TestTypes.Track(s"track-$i", (120 seconds).toMillis)
              }
              ctx.consumerProbe.expectWsConsumerKeyValueResultAvro(
                expectedTopic = ctx.topicName,
                expectedKey = Option(TestTypes.TestKey(s"foo-$i", 1234567L)),
                expectedValue =
                  TestTypes.Album(s"artist-$i", s"title-$i", expectedTracks)
              )
            }

          }
        }

      "consume messages with String key and value" in
        plainAvroConsumerContext(
          topic = "test-topic-11",
          keyType = Some(StringType),
          valType = StringType,
          numMessages = 10
        ) { ctx =>
          implicit val kcfg = ctx.embeddedKafkaConfig

          val outPath = "/socket/out?" +
            "clientId=test-11" +
            "&groupId=test-group-11" +
            s"&topic=${ctx.topicName.value}" +
            s"&socketPayload=${AvroPayload.name}" +
            s"&keyType=${StringType.name}" +
            s"&valType=${StringType.name}" +
            "&autoCommit=false"

          val (rk, rv) =
            consumeFirstKeyedMessageFrom[String, String](ctx.topicName.value)
          rk mustBe "foo-1"
          rv mustBe "artist-1"

          WS(outPath, ctx.consumerProbe.flow) ~> ctx.route ~> check {
            isWebSocketUpgrade mustBe true

            forAll(1 to 10) { i =>
              ctx.consumerProbe.expectWsConsumerResultAvro[String, String](
                expectedTopic = ctx.topicName,
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
        }

      "consume messages with String value" in
        plainAvroConsumerContext(
          topic = "test-topic-12",
          keyType = None,
          valType = StringType,
          numMessages = 10
        ) { ctx =>
          implicit val kcfg = ctx.embeddedKafkaConfig

          val outPath = "/socket/out?" +
            "clientId=test-12" +
            "&groupId=test-group-12" +
            s"&topic=${ctx.topicName.value}" +
            s"&socketPayload=${AvroPayload.name}" +
            s"&valType=${StringType.name}" +
            "&autoCommit=false"

          val rv = consumeFirstMessageFrom[String](ctx.topicName.value)
          rv mustBe "artist-1"

          WS(outPath, ctx.consumerProbe.flow) ~> ctx.route ~> check {
            isWebSocketUpgrade mustBe true

            forAll(1 to 10) { i =>
              ctx.consumerProbe.expectWsConsumerResultAvro[String, String](
                expectedTopic = ctx.topicName,
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
        }

      "consume message with Long key and String value" in
        plainAvroConsumerContext(
          topic = "test-topic-12",
          keyType = Some(LongType),
          valType = StringType,
          numMessages = 10
        ) { ctx =>
          implicit val kcfg = ctx.embeddedKafkaConfig

          val outPath = "/socket/out?" +
            "clientId=test-13" +
            "&groupId=test-group-13" +
            s"&topic=${ctx.topicName.value}" +
            s"&socketPayload=${AvroPayload.name}" +
            s"&keyType=${LongType.name}" +
            s"&valType=${StringType.name}" +
            "&autoCommit=false"

          val (rk, rv) =
            consumeFirstKeyedMessageFrom[Long, String](ctx.topicName.value)
          rk mustBe 1L
          rv mustBe "artist-1"

          WS(outPath, ctx.consumerProbe.flow) ~> ctx.route ~> check {
            isWebSocketUpgrade mustBe true

            forAll(1 to 10) { i =>
              ctx.consumerProbe.expectWsConsumerResultAvro[Long, String](
                expectedTopic = ctx.topicName,
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
        }

      "return HTTP 400 when attempting to produce to a non-existing topic" in
        plainProducerContext() { ctx =>
          implicit val wsClient = ctx.producerProbe

          val nonExistingTopic = TopicName("non-existing-topic")

          val uri = baseProducerUri(
            topicName = nonExistingTopic,
            keyType = StringType,
            valType = StringType,
            payloadType = AvroPayload
          )

          WS(uri, wsClient.flow) ~> Route.seal(ctx.route) ~> check {
            status mustBe BadRequest
          }
        }

      "return HTTP 400 when attempting to consume from non-existing topic" in
        plainAvroConsumerContext(
          topic = "test-topic-100",
          keyType = Some(AvroType),
          valType = AvroType,
          numMessages = 0,
          prePopulate = false
        ) { ctx =>
          val topicName = "non-existing-topic"
          val out = "/socket/out?" +
            "clientId=test-100" +
            "&groupId=test-group-100" +
            s"&topic=$topicName" +
            s"&socketPayload=${AvroPayload.name}" +
            s"&keyType=${AvroPayload.name}" +
            s"&valType=${AvroPayload.name}" +
            "&autoCommit=false"

          WS(out, ctx.consumerProbe.flow) ~> Route.seal(ctx.route) ~> check {
            status mustBe BadRequest
          }
        }

      // scalastyle:off line.size.limit
      "return a HTTP 401 when using wrong credentials to establish an outbound connection to a secured cluster" in
        // scalastyle:on line.size.limit
        secureKafkaAvroConsumerContext(
          topic = "restricted-1",
          keyType = Some(AvroType),
          valType = AvroType,
          numMessages = 0,
          prePopulate = false
        ) { ctx =>
          val out = "/socket/out?" +
            "clientId=test-101" +
            "&groupId=test-group-101" +
            s"&topic=${ctx.topicName.value}" +
            s"&socketPayload=${AvroPayload.name}" +
            s"&keyType=${AvroPayload.name}" +
            s"&valType=${AvroPayload.name}" +
            "&autoCommit=false"

          val wrongCreds = addKafkaCreds(BasicHttpCredentials("bad", "user"))

          WS(out, ctx.consumerProbe.flow) ~>
            wrongCreds ~>
            Route.seal(ctx.route) ~>
            check {
              status mustBe Unauthorized
            }
        }

      // scalastyle:off line.size.limit
      "return a HTTP 401 when using wrong credentials to establish an inbound connection to a secured cluster" in
        // scalastyle:on line.size.limit
        secureKafkaClusterProducerContext(
          topic = "restricted-2"
        ) { implicit ctx =>
          implicit val wsClient = ctx.producerProbe
          val baseUri = baseProducerUri(
            topicName = ctx.topicName,
            payloadType = AvroPayload,
            keyType = NoType,
            valType = StringType
          )

          val wrongCreds = BasicHttpCredentials("bad", "user")

          checkWebSocket(
            uri = baseUri,
            routes = Route.seal(ctx.route),
            kafkaCreds = Some(wrongCreds)
          ) {
            status mustBe Unauthorized
          }
        }

      "produce messages to a secured cluster" in
        secureKafkaClusterProducerContext(
          "secure-topic-1"
        ) { ctx =>
          implicit val wsClient = ctx.producerProbe

          val messages = createAvroProducerRecordNoneAvro(1)

          produceAndCheckAvro(
            topic = ctx.topicName,
            routes = Route.seal(ctx.route),
            keyType = None,
            valType = AvroType,
            messages = messages,
            kafkaCreds = Some(creds)
          )
        }

      "allow connections with a valid bearer token when OpenID is enabled" in
        withEmbeddedOpenIdConnectServerAndToken() {
          case (_, _, _, cfg, token) =>
            secureServerProducerContext(
              topic = "openid-1",
              serverOpenIdCfg = Option(cfg)
            ) { ctx =>
              implicit val wsClient = ctx.producerProbe

              val messages = createAvroProducerRecordNoneAvro(1)

              produceAndCheckAvro(
                topic = ctx.topicName,
                routes = Route.seal(ctx.route),
                keyType = None,
                valType = AvroType,
                messages = messages,
                creds = Some(token.bearerToken),
                kafkaCreds = Some(creds)
              )
            }
        }

      "return HTTP 401 when bearer token is invalid with OpenID enabled" in
        withEmbeddedOpenIdConnectServerAndClient() { case (_, _, _, cfg) =>
          secureServerProducerContext(
            topic = "openid-2",
            serverOpenIdCfg = Option(cfg)
          ) { ctx =>
            implicit val wsClient = ctx.producerProbe

            val token = AccessToken("Bearer", "foo.bar.baz", 3600L, None)

            val baseUri = baseProducerUri(
              topicName = ctx.topicName,
              payloadType = AvroPayload,
              keyType = NoType,
              valType = StringType
            )

            checkWebSocket(
              uri = baseUri,
              routes = Route.seal(ctx.route),
              creds = Some(token.bearerToken),
              kafkaCreds = Some(creds)
            ) {
              status mustBe Unauthorized
            }
          }
        }
    }
  }
}
