package net.scalytica.kafka.wsproxy

import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server._
import akka.http.scaladsl.testkit.{RouteTestTimeout, WSProbe}
import net.manub.embeddedkafka.Codecs._
import net.scalytica.kafka.wsproxy.models.Formats.{NoType, StringType}
import net.scalytica.test._
import org.scalatest.Inspectors.forAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Minutes, Span}
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{EitherValues, OptionValues}

import scala.concurrent.duration._

// scalastyle:off magic.number
class WebSocketRoutesJsonSpec
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

  "Using JSON payloads with WebSockets" when {

    "the server routes" should {

      "produce messages with String key and value" in
        defaultProducerContext("test-topic-1") {
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

      "produce messages with String value" in
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

      "produce messages with headers and String key and value" in
        defaultProducerContext("test-topic-3") {
          case (ekc, _, testRoutes, wsc) =>
            implicit val wsClient = wsc
            implicit val kcfg     = ekc

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

      "produce messages with headers and String values" in
        defaultProducerContext("test-topic-4") {
          case (ekc, _, testRoutes, wsc) =>
            implicit val wsClient = wsc
            implicit val kcfg     = ekc

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

      "consume messages with String key and value" in
        withRunningKafkaOnFoundPort(embeddedKafkaConfig) { implicit kcfg =>
          implicit val wsCfg = appTestConfig(kcfg.kafkaPort)

          val topicName = "test-topic-5"
          initTopic(topicName)

          implicit val wsConsumerProbe = WSProbe()
          val producerProbe            = WSProbe()
          val (sdcStream, testRoutes)  = TestServerRoutes.wsProxyRoutes
          val ctrl                     = sdcStream.run()
          val routes                   = Route.seal(testRoutes)

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
          val res =
            consumeNumberKeyedMessagesFrom[String, String](topicName, 10)
          res must have size 10
          forAll(res) {
            case (k, v) =>
              k must startWith("foo-")
              v must startWith("bar-")
          }
          WS(outPath, wsConsumerProbe.flow) ~> routes ~> check {
            isWebSocketUpgrade mustBe true

            forAll(1 to 10) { i =>
              wsConsumerProbe
                .expectWsConsumerKeyValueResultJson[String, String](
                  expectedTopic = topicName,
                  expectedKey = s"foo-$i",
                  expectedValue = s"bar-$i"
                )
            }

          }

          ctrl.shutdown()
        }

      "consume messages with String value" in
        withRunningKafkaOnFoundPort(embeddedKafkaConfig) { implicit kcfg =>
          implicit val wsCfg = appTestConfig(kcfg.kafkaPort)

          val topicName = "test-topic-6"
          initTopic(topicName)

          implicit val wsConsumerProbe = WSProbe()
          val producerProbe            = WSProbe()
          val (sdcStream, testRoutes)  = TestServerRoutes.wsProxyRoutes
          val ctrl                     = sdcStream.run()
          val routes                   = Route.seal(testRoutes)

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
              status mustBe BadRequest
            }
        }

      "consume messages from a secured cluster" in
        secureProducerContext("secure-topic-2") {
          case (ekc, _, testRoutes, wsProducerClient) =>
            implicit val kcfg = ekc
            val routes        = Route.seal(testRoutes)
            val messages      = produceValueJson(10)

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
              addKafkaCreds(creds) ~>
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

}
