package net.scalytica.kafka.wsproxy.web

import akka.http.scaladsl.server._
import io.github.embeddedkafka.Codecs._
import net.scalytica.kafka.wsproxy.config.Configuration.CustomJwtCfg
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
import net.scalytica.kafka.wsproxy.web.SocketProtocol.AvroPayload
import net.scalytica.test._
import org.scalatest.Assertion
import org.scalatest.Inspectors.forAll

import scala.concurrent.duration._

// scalastyle:off magic.number
class WebSocketRoutesAvroSpec extends BaseWebSocketRoutesSpec {

  implicit val testKeyDeserializer = TestSerdes.keySerdes.deserializer()
  implicit val albumDeserializer   = TestSerdes.valueSerdes.deserializer()
  implicit val longDeserializer    = TestSerdes.longSerdes.deserializer()

  override protected val testTopicPrefix: String = "avro-test-topic"

  protected def verifyTestKeyAndAlbum(
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

    "both kafka and the proxy is unsecured" should {

      "produce messages with Avro key and value" in
        plainProducerContext(nextTopic) { ctx =>
          implicit val wsClient = ctx.producerProbe

          val messages = createAvroProducerRecordAvroAvro(1)

          produceAndAssertAvro(
            producerId = producerId("avro", topicCounter),
            instanceId = None,
            topic = ctx.topicName,
            routes = Route.seal(ctx.route),
            keyType = Some(AvroType),
            valType = AvroType,
            messages = messages
          )
        }

      "produce messages with String key and Avro value" in
        plainProducerContext(nextTopic) { ctx =>
          implicit val wsClient = ctx.producerProbe

          val messages = createAvroProducerRecordStringBytes(1)

          produceAndAssertAvro(
            producerId = producerId("avro", topicCounter),
            instanceId = None,
            topic = ctx.topicName,
            routes = Route.seal(ctx.route),
            keyType = Some(StringType),
            valType = AvroType,
            messages = messages
          )
        }

      "produce messages with String key and Avro value with headers" in
        plainProducerContext(nextTopic) { ctx =>
          implicit val wsClient = ctx.producerProbe

          val messages =
            createAvroProducerRecordStringBytes(1, withHeaders = true)

          produceAndAssertAvro(
            producerId = producerId("avro", topicCounter),
            instanceId = None,
            topic = ctx.topicName,
            routes = Route.seal(ctx.route),
            keyType = Some(StringType),
            valType = AvroType,
            messages = messages
          )
        }

      "produce messages with Avro value" in
        plainProducerContext(nextTopic) { ctx =>
          implicit val wsClient = ctx.producerProbe

          val messages = createAvroProducerRecordNoneAvro(1)

          produceAndAssertAvro(
            producerId = producerId("avro", topicCounter),
            instanceId = None,
            topic = ctx.topicName,
            routes = Route.seal(ctx.route),
            keyType = None,
            valType = AvroType,
            messages = messages
          )
        }

      "produce messages with String key and Avro value and message ID" in
        plainProducerContext(nextTopic) { ctx =>
          implicit val wsClient = ctx.producerProbe

          val messages =
            createAvroProducerRecordStringBytes(1, withMessageId = true)

          produceAndAssertAvro(
            producerId = producerId("avro", topicCounter),
            instanceId = None,
            topic = ctx.topicName,
            routes = Route.seal(ctx.route),
            keyType = Some(StringType),
            valType = AvroType,
            messages = messages,
            validateMessageId = true
          )
        }

      "consume messages with Avro key and value" in
        plainAvroConsumerContext(
          topic = nextTopic,
          keyType = Some(AvroType),
          valType = AvroType,
          numMessages = 10
        ) { ctx =>
          implicit val kcfg = ctx.embeddedKafkaConfig

          val outPath = "/socket/out?" +
            s"clientId=avro-test-$topicCounter" +
            s"&groupId=avro-test-group-$topicCounter" +
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
          topic = nextTopic,
          keyType = Some(StringType),
          valType = StringType,
          numMessages = 10
        ) { ctx =>
          implicit val kcfg = ctx.embeddedKafkaConfig

          val outPath = "/socket/out?" +
            s"clientId=avro-test-$topicCounter" +
            s"&groupId=avro-test-group-$topicCounter" +
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

      "consume messages with String key and value with headers" in
        plainAvroConsumerContext(
          topic = nextTopic,
          keyType = Some(StringType),
          valType = StringType,
          numMessages = 10,
          withHeaders = true
        ) { ctx =>
          implicit val kcfg = ctx.embeddedKafkaConfig

          val outPath = "/socket/out?" +
            s"clientId=avro-test-$topicCounter" +
            s"&groupId=avro-test-group-$topicCounter" +
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
                expectHeaders = true,
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
          topic = nextTopic,
          keyType = None,
          valType = StringType,
          numMessages = 10
        ) { ctx =>
          implicit val kcfg = ctx.embeddedKafkaConfig

          val outPath = "/socket/out?" +
            s"clientId=avro-test-$topicCounter" +
            s"&groupId=avro-test-group-$topicCounter" +
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
          topic = nextTopic,
          keyType = Some(LongType),
          valType = StringType,
          numMessages = 10
        ) { ctx =>
          implicit val kcfg = ctx.embeddedKafkaConfig

          val outPath = "/socket/out?" +
            s"clientId=avro-test-$topicCounter" +
            s"&groupId=avro-test-group-$topicCounter" +
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

    }

    "kafka is secure and the server is unsecured" should {

      "produce messages to a secured cluster" in
        secureKafkaClusterProducerContext(topic = nextTopic) { ctx =>
          implicit val wsClient = ctx.producerProbe

          val messages = createAvroProducerRecordNoneAvro(1)

          produceAndAssertAvro(
            producerId = producerId("avro", topicCounter),
            instanceId = None,
            topic = ctx.topicName,
            routes = Route.seal(ctx.route),
            keyType = None,
            valType = AvroType,
            messages = messages,
            kafkaCreds = Some(creds)
          )
        }

      "consume messages with Avro key and value" in
        secureKafkaAvroConsumerContext(
          topic = nextTopic,
          keyType = Some(AvroType),
          valType = AvroType,
          numMessages = 10
        ) { ctx =>
          implicit val kcfg = ctx.embeddedKafkaConfig

          val outPath = "/socket/out?" +
            s"clientId=avro-test-$topicCounter" +
            s"&groupId=avro-test-group-$topicCounter" +
            s"&topic=${ctx.topicName.value}" +
            s"&socketPayload=${AvroPayload.name}" +
            s"&keyType=${AvroType.name}" +
            s"&valType=${AvroType.name}" +
            "&autoCommit=false"

          val (rk, rv) =
            consumeFirstKeyedMessageFrom[TestKey, Album](ctx.topicName.value)

          verifyTestKeyAndAlbum(rk, rv)

          WS(outPath, ctx.consumerProbe.flow) ~>
            addKafkaCreds(creds) ~>
            ctx.route ~>
            check {
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
    }

    "kafka is secure and the server is secured with OpenID Connect" should {

      "allow producer connections providing a valid JWT token when OpenID" +
        " is enabled" in withOpenIdConnectServerAndToken(useJwtCreds = false) {
          case (_, _, _, cfg, token) =>
            secureServerProducerContext(
              topic = nextTopic,
              serverOpenIdCfg = Option(cfg)
            ) { ctx =>
              implicit val wsClient = ctx.producerProbe

              val messages = createAvroProducerRecordNoneAvro(1)

              produceAndAssertAvro(
                producerId = producerId("avro", topicCounter),
                instanceId = None,
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

      "allow connections providing a valid JWT token containing the Kafka " +
        "credentials when OpenID is enabled" in
        withOpenIdConnectServerAndToken(useJwtCreds = true) {
          case (_, _, _, cfg, token) =>
            secureServerProducerContext(
              topic = nextTopic,
              serverOpenIdCfg = Option(cfg)
            ) { ctx =>
              implicit val wsClient = ctx.producerProbe

              val messages = createAvroProducerRecordNoneAvro(1)

              produceAndAssertAvro(
                producerId = producerId("avro", topicCounter),
                instanceId = None,
                topic = ctx.topicName,
                routes = Route.seal(ctx.route),
                keyType = None,
                valType = AvroType,
                messages = messages,
                creds = Some(token.bearerToken)
              )
            }
        }

      "allow connections providing a valid JWT token containing the Kafka " +
        "credentials when OpenID is enabled using a custom JWT config" in
        withOpenIdConnectServerAndToken(useJwtCreds = true) {
          case (_, _, _, cfg, token) =>
            val oidcCfg =
              cfg.copy(customJwt =
                Some(
                  CustomJwtCfg(
                    jwtKafkaUsernameKey = jwtKafkaCredsUsernameKey,
                    jwtKafkaPasswordKey = jwtKafkaCredsPasswordKey
                  )
                )
              )
            secureServerProducerContext(
              topic = nextTopic,
              serverOpenIdCfg = Option(oidcCfg)
            ) { ctx =>
              implicit val wsClient = ctx.producerProbe

              val messages = createAvroProducerRecordNoneAvro(1)

              produceAndAssertAvro(
                producerId = producerId("avro", topicCounter),
                instanceId = None,
                topic = ctx.topicName,
                routes = Route.seal(ctx.route),
                keyType = None,
                valType = AvroType,
                messages = messages,
                creds = Some(token.bearerToken),
                kafkaCreds = None
              )
            }
        }

    }
  }
}
