package net.scalytica.kafka.wsproxy

import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server._
import akka.http.scaladsl.testkit.{RouteTestTimeout, WSProbe}
import net.manub.embeddedkafka.Codecs.stringDeserializer
import net.scalytica.kafka.wsproxy.models.Formats.{NoType, StringType}
import net.scalytica.kafka.wsproxy.models.TopicName
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
    with WsProxyConsumerKafkaSpec
    with MockOpenIdServer
    with TestDataGenerators {

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(2, Minutes))

  implicit val timeout = RouteTestTimeout(20 seconds)

  import TestServerRoutes.{serverErrorHandler, serverRejectionHandler}

  "Using JSON payloads with WebSockets" when {

    "the server routes" should {

      "produce messages with String key and value" in
        plainProducerContext("test-topic-1") { ctx =>
          implicit val wsClient = ctx.producerProbe

          val msgs = createJsonKeyValue(1)

          produceAndCheckJson(
            topic = ctx.topicName,
            keyType = StringType,
            valType = StringType,
            routes = Route.seal(ctx.route),
            messages = msgs
          )
        }

      "produce messages with String value" in
        plainProducerContext("test-topic-2") { ctx =>
          implicit val wsClient = ctx.producerProbe

          val msgs = createJsonValue(1)

          produceAndCheckJson(
            topic = ctx.topicName,
            keyType = NoType,
            valType = StringType,
            routes = Route.seal(ctx.route),
            messages = msgs
          )
        }

      "produce messages with headers and String key and value" in
        plainProducerContext("test-topic-3") { ctx =>
          implicit val wsClient = ctx.producerProbe
          implicit val kcfg     = ctx.embeddedKafkaConfig

          val msgs = createJsonKeyValue(1, withHeaders = true)

          produceAndCheckJson(
            topic = ctx.topicName,
            keyType = StringType,
            valType = StringType,
            routes = Route.seal(ctx.route),
            messages = msgs
          )

          // validate the topic contents
          val (k, v) =
            consumeFirstKeyedMessageFrom[String, String](ctx.topicName.value)
          k mustBe "foo-1"
          v mustBe "bar-1"
        }

      "produce messages with headers and String values" in
        plainProducerContext("test-topic-4") { ctx =>
          implicit val wsClient = ctx.producerProbe
          implicit val kcfg     = ctx.embeddedKafkaConfig

          val msgs = createJsonValue(1, withHeaders = true)

          produceAndCheckJson(
            topic = ctx.topicName,
            keyType = NoType,
            valType = StringType,
            routes = Route.seal(ctx.route),
            messages = msgs
          )

          // validate the topic contents
          consumeFirstMessageFrom[String](ctx.topicName.value) mustBe "bar-1"
        }

      "consume messages with String key and value" in
        plainJsonConsumerContext(
          topic = "test-topic-5",
          keyType = Some(StringType),
          valType = StringType,
          numMessages = 10
        ) { ctx =>
          implicit val kcfg = ctx.embeddedKafkaConfig

          val out = "/socket/out?" +
            "clientId=test-5" +
            "&groupId=test-group-5" +
            s"&topic=${ctx.topicName.value}" +
            s"&keyType=${StringType.name}" +
            s"&valType=${StringType.name}" +
            "&autoCommit=false"

          // validate the topic contents
          val res =
            consumeNumberKeyedMessagesFrom[String, String](
              topic = ctx.topicName.value,
              number = 10
            )
          res must have size 10
          forAll(res) { case (k, v) =>
            k must startWith("foo-")
            v must startWith("bar-")
          }
          WS(out, ctx.consumerProbe.flow) ~> ctx.route ~> check {
            isWebSocketUpgrade mustBe true

            forAll(1 to 10) { i =>
              ctx.consumerProbe
                .expectWsConsumerKeyValueResultJson[String, String](
                  expectedTopic = ctx.topicName,
                  expectedKey = s"foo-$i",
                  expectedValue = s"bar-$i"
                )
            }
          }
        }

      "consume messages with String value" in
        plainJsonConsumerContext(
          topic = "test-topic-6",
          keyType = None,
          valType = StringType,
          numMessages = 10
        ) { ctx =>
          implicit val kcfg = ctx.embeddedKafkaConfig

          val out = "/socket/out?" +
            "clientId=test-6" +
            "&groupId=test-group-6" +
            s"&topic=${ctx.topicName.value}" +
            s"&valType=${StringType.name}" +
            "&autoCommit=false"

          consumeFirstMessageFrom[String](ctx.topicName.value) mustBe "bar-1"

          WS(out, ctx.consumerProbe.flow) ~> ctx.route ~> check {
            isWebSocketUpgrade mustBe true

            forAll(1 to 10) { i =>
              ctx.consumerProbe.expectWsConsumerValueResultJson[String](
                expectedTopic = ctx.topicName,
                expectedValue = s"bar-$i"
              )
            }
          }
        }

      "return HTTP 400 when attempting to produce to a non-existing topic" in
        plainProducerContext("test-topic-7") { ctx =>
          val topicName = TopicName("non-existing-topic")

          val uri = baseProducerUri(
            topicName = topicName,
            keyType = StringType,
            valType = StringType
          )

          WS(uri, ctx.producerProbe.flow) ~> Route.seal(ctx.route) ~> check {
            status mustBe BadRequest
          }
        }

      "consume messages from a secured cluster" in
        secureKafkaJsonConsumerContext(
          topic = "secure-topic-2",
          keyType = None,
          valType = StringType,
          numMessages = 10
        ) { ctx =>
          implicit val kcfg = ctx.embeddedKafkaConfig

          val out = "/socket/out?" +
            "clientId=test-102" +
            "&groupId=test-group-102" +
            s"&topic=${ctx.topicName.value}" +
            s"&valType=${StringType.name}" +
            "&autoCommit=true"

          consumeFirstMessageFrom[String](ctx.topicName.value) mustBe "bar-1"

          WS(out, ctx.consumerProbe.flow) ~>
            addKafkaCreds(creds) ~>
            ctx.route ~>
            check {
              isWebSocketUpgrade mustBe true

              forAll(1 to 10) { i =>
                ctx.consumerProbe.expectWsConsumerValueResultJson[String](
                  expectedTopic = ctx.topicName,
                  expectedValue = s"bar-$i"
                )
              }
            }
        }

      "reject a new connection if the consumer already exists" in
        plainJsonConsumerContext(
          topic = "test-topic-8",
          keyType = None,
          valType = StringType,
          partitions = 2,
          numMessages = 0,
          prePopulate = false
        ) { ctx =>
          val rejectionMsg = "WebSocket for consumer test-8 in session " +
            "test-group-8 not established because a consumer with the" +
            " same ID is already registered"

          val out = "/socket/out?" +
            "clientId=test-8" +
            "&groupId=test-group-8" +
            s"&topic=${ctx.topicName.value}" +
            s"&valType=${StringType.name}" +
            "&autoCommit=false"

          val probe1 = WSProbe()

          WS(out, probe1.flow) ~> ctx.route ~> check {
            isWebSocketUpgrade mustBe true

            // Make sure consumer socket 1 is ready and registered in session
            Thread.sleep((4 seconds).toMillis)

            WS(out, ctx.consumerProbe.flow) ~> ctx.route ~> check {
              rejection match {
                case vr: ValidationRejection => vr.message mustBe rejectionMsg
                case _                       => fail("Unexpected Rejection")
              }
            }
          }
        }

      "reject a new connection if the consumer limit has been reached" in
        plainJsonConsumerContext(
          topic = "test-topic-9",
          keyType = None,
          valType = StringType,
          numMessages = 0,
          prePopulate = false
        ) { ctx =>
          val rejectionMsg =
            "The maximum number of WebSockets for session test-group-9 " +
              "has been reached. Limit is 1"

          val out = (cid: String) =>
            "/socket/out?" +
              s"clientId=test-9$cid" +
              "&groupId=test-group-9" +
              s"&topic=${ctx.topicName.value}" +
              s"&valType=${StringType.name}" +
              "&autoCommit=false"

          val probe1 = WSProbe()

          WS(out("a"), probe1.flow) ~> ctx.route ~> check {
            isWebSocketUpgrade mustBe true

            // Make sure consumer socket 1 is ready and registered in session
            Thread.sleep((4 seconds).toMillis)

            WS(out("b"), ctx.consumerProbe.flow) ~> ctx.route ~> check {
              rejection match {
                case vr: ValidationRejection => vr.message mustBe rejectionMsg
                case _                       => fail("Unexpected Rejection")
              }
            }
          }
        }
    }
  }

}
