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
import org.scalatest.OptionValues

import scala.concurrent.duration._

// scalastyle:off magic.number
class WebSocketRoutesJsonSpec
    extends AnyWordSpec
    with OptionValues
    with ScalaFutures
    with WsProxyConsumerKafkaSpec
    with MockOpenIdServer
    with TestDataGenerators {

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(2, Minutes))

  implicit val timeout = RouteTestTimeout(20 seconds)

  import TestServerRoutes.{serverErrorHandler, serverRejectionHandler}

  private[this] var topicCounter = 0

  private[this] def nextTopic: String = {
    topicCounter = topicCounter + 1
    s"json-test-topic-$topicCounter"
  }

  "Using JSON payloads with WebSockets" when {

    "the server routes" should {

      "produce messages with String key and value" in
        plainProducerContext(nextTopic) { ctx =>
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
        plainProducerContext(nextTopic) { ctx =>
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
        plainProducerContext(nextTopic) { ctx =>
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
        plainProducerContext(nextTopic) { ctx =>
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

      "produce messages with headers, String key and value and message ID" in
        plainProducerContext(nextTopic) { ctx =>
          implicit val wsClient = ctx.producerProbe
          implicit val kcfg     = ctx.embeddedKafkaConfig

          val messages =
            createJsonKeyValue(1, withHeaders = true, withMessageId = true)

          produceAndCheckJson(
            topic = ctx.topicName,
            keyType = StringType,
            valType = StringType,
            routes = Route.seal(ctx.route),
            messages = messages,
            validateMessageId = true
          )

          // validate the topic contents
          val (k, v) =
            consumeFirstKeyedMessageFrom[String, String](ctx.topicName.value)
          k mustBe "foo-1"
          v mustBe "bar-1"
        }

      "consume messages with String key and value" in
        plainJsonConsumerContext(
          topic = nextTopic,
          keyType = Some(StringType),
          valType = StringType,
          numMessages = 10
        ) { ctx =>
          implicit val kcfg = ctx.embeddedKafkaConfig

          val out = "/socket/out?" +
            s"clientId=json-test-$topicCounter" +
            s"&groupId=json-test-group-$topicCounter" +
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

      "consume messages with String key and value and headers" in
        plainJsonConsumerContext(
          topic = nextTopic,
          keyType = Some(StringType),
          valType = StringType,
          numMessages = 10,
          withHeaders = true
        ) { ctx =>
          implicit val kcfg = ctx.embeddedKafkaConfig

          val out = "/socket/out?" +
            s"clientId=json-test-$topicCounter" +
            s"&groupId=json-test-group-$topicCounter" +
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
                  expectedValue = s"bar-$i",
                  expectHeaders = true
                )
            }
          }
        }

      "consume messages with String value" in
        plainJsonConsumerContext(
          topic = nextTopic,
          keyType = None,
          valType = StringType,
          numMessages = 10
        ) { ctx =>
          implicit val kcfg = ctx.embeddedKafkaConfig

          val out = "/socket/out?" +
            s"clientId=json-test-$topicCounter" +
            s"&groupId=json-test-group-$topicCounter" +
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
        plainProducerContext(nextTopic) { ctx =>
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
          topic = nextTopic,
          keyType = None,
          valType = StringType,
          numMessages = 10
        ) { ctx =>
          implicit val kcfg = ctx.embeddedKafkaConfig

          val out = "/socket/out?" +
            s"clientId=json-test-$topicCounter" +
            s"&groupId=json-test-group-$topicCounter" +
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
          topic = nextTopic,
          keyType = None,
          valType = StringType,
          partitions = 2,
          numMessages = 0,
          prePopulate = false
        ) { ctx =>
          val rejectionMsg =
            s"WebSocket for consumer json-test-$topicCounter in session " +
              s"json-test-group-$topicCounter not established because a" +
              " consumer with the same ID is already registered"

          val out = "/socket/out?" +
            s"clientId=json-test-$topicCounter" +
            s"&groupId=json-test-group-$topicCounter" +
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
          topic = nextTopic,
          keyType = None,
          valType = StringType,
          numMessages = 0,
          prePopulate = false
        ) { ctx =>
          val rejectionMsg =
            s"The maximum number of WebSockets for session " +
              s"json-test-group-$topicCounter has been reached. Limit is 1"

          val out = (cid: String) =>
            "/socket/out?" +
              s"clientId=json-test-$topicCounter$cid" +
              s"&groupId=json-test-group-$topicCounter" +
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
