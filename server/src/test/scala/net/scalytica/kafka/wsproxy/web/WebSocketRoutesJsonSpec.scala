package net.scalytica.kafka.wsproxy.web

import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server._
import akka.http.scaladsl.testkit.WSProbe
import io.github.embeddedkafka.Codecs.stringDeserializer
import net.scalytica.kafka.wsproxy.models.Formats.{NoType, StringType}
import net.scalytica.kafka.wsproxy.models.{
  TopicName,
  WsProducerId,
  WsProducerInstanceId
}
import net.scalytica.kafka.wsproxy.web.SocketProtocol.JsonPayload
import net.scalytica.test._
import org.scalatest.Inspectors.forAll
import org.scalatest.tagobjects.Retryable
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.duration._

// scalastyle:off magic.number
class WebSocketRoutesJsonSpec
    extends AnyWordSpec
    with BaseWebSocketRoutesSpec
    with FlakyTests {

  override protected val testTopicPrefix: String = "json-test-topic"

  private[this] def shortProducerUri(pid: String, iid: Option[String])(
      implicit ctx: ProducerContext
  ) = {
    baseProducerUri(
      producerId = WsProducerId(pid),
      instanceId = iid.map(WsProducerInstanceId.apply),
      topicName = ctx.topicName,
      payloadType = JsonPayload
    )
  }

  "Using JSON payloads with WebSockets" when {

    "the server routes" should {

      "reject producer connection when the required clientId is not set" in
        plainProducerContext(nextTopic) { implicit ctx =>
          assertRejectMissingProducerId()
        }

      "reject producer connection when sessions are enabled and instanceId " +
        "is not set" in
        plainProducerContext(nextTopic, useProducerSessions = true) {
          implicit ctx =>
            assertRejectMissingInstanceId(useSession = true)
        }

      "allow producer connection when sessions are enabled and instanceId " +
        "is not set" in
        plainProducerContext(nextTopic) { implicit ctx =>
          assertRejectMissingInstanceId(useSession = false)
        }

      "reject producer connection when the required topic is not set" in
        plainProducerContext(nextTopic) { implicit ctx =>
          assertRejectMissingTopicName()
        }

      "produce messages with String key and value" in
        plainProducerContext(nextTopic) { ctx =>
          implicit val wsClient = ctx.producerProbe

          val msgs = createJsonKeyValue(1)

          produceAndAssertJson(
            producerId = producerId("json", topicCounter),
            instanceId = None,
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

          produceAndAssertJson(
            producerId = producerId("json", topicCounter),
            instanceId = None,
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

          produceAndAssertJson(
            producerId = producerId("json", topicCounter),
            instanceId = None,
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

          produceAndAssertJson(
            producerId = producerId("json", topicCounter),
            instanceId = None,
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

          produceAndAssertJson(
            producerId = producerId("json", topicCounter),
            instanceId = None,
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

      "allow a producer to reconnect when sessions are not enabled" in
        plainProducerContext(nextTopic) { implicit ctx =>
          val in = shortProducerUri(s"json-test-$topicCounter", None)

          withEmbeddedServer(
            routes = ctx.route,
            completionWaitDuration = Some(10 seconds)
          ) { (host, port) =>
            // validate first request
            forAll(1 to 4) { _ =>
              assertWSRequest(host, port, in)(initialDelay = 2 seconds)
            }
          }
        }

      "allow producer to reconnect when sessions are enabled and limit is 1" in
        plainProducerContext(nextTopic, useProducerSessions = true) {
          implicit ctx =>
            val in =
              shortProducerUri("limit-test-producer-2", Some("instance-1"))

            withEmbeddedServer(
              routes = ctx.route,
              completionWaitDuration = Some(10 seconds)
            ) { (host, port) =>
              // validate first request
              forAll(1 to 4) { _ =>
                assertWSRequest(host, port, in)(initialDelay = 2 seconds)
              }
            }
        }

      "allow producer to reconnect when sessions are enabled and limit is 2" in
        plainProducerContext(nextTopic, useProducerSessions = true) {
          implicit ctx =>
            val wsClient = ctx.producerProbe

            lazy val in = (instance: String) =>
              shortProducerUri("limit-test-producer-2", Some(instance))

            withEmbeddedServer(
              routes = ctx.route,
              completionWaitDuration = Some(10 seconds)
            ) { (host, port) =>
              WS(in("instance-1"), wsClient.flow) ~> ctx.route ~> check {
                isWebSocketUpgrade mustBe true
                // Make sure socket 1 is ready and registered in session
                Thread.sleep((4 seconds).toMillis)
                // validate first request
                forAll(1 to 4) { _ =>
                  assertWSRequest(host, port, in(s"instance-2"))(initialDelay =
                    2 seconds
                  )
                }
              }
            }
        }

      "not enforce producer session limits when max-connections limit is 0" in
        plainProducerContext(nextTopic, useProducerSessions = true) {
          implicit ctx =>
            val wsClient1 = ctx.producerProbe
            val wsClient2 = WSProbe()
            val wsClient3 = WSProbe()

            lazy val in = (instance: String) =>
              shortProducerUri("limit-test-producer-3", Some(instance))

            WS(in("instance-1"), wsClient1.flow) ~> ctx.route ~> check {
              isWebSocketUpgrade mustBe true

              WS(in("instance-2"), wsClient2.flow) ~> ctx.route ~> check {
                isWebSocketUpgrade mustBe true

                // Make sure both sockets are ready and registered in session
                Thread.sleep((4 seconds).toMillis)

                WS(in("instance-3"), wsClient3.flow) ~> ctx.route ~> check {
                  isWebSocketUpgrade mustBe true
                }
              }
            }
        }

      "reject a new connection if the producer limit has been reached" in
        plainProducerContext(nextTopic, useProducerSessions = true) {
          implicit ctx =>
            val wsClient1 = ctx.producerProbe
            val wsClient2 = WSProbe()

            val in = (instance: String) =>
              // Producer ID is specifically defined in application-test.conf
              shortProducerUri("limit-test-producer-1", Some(instance))

            WS(in("instance-1"), wsClient1.flow) ~> ctx.route ~> check {
              isWebSocketUpgrade mustBe true
              // Make sure consumer socket 1 is ready and registered in session
              Thread.sleep((4 seconds).toMillis)

              WS(in("instance-2"), wsClient2.flow) ~> ctx.route ~> check {
                status mustBe BadRequest
                val res = responseAs[String]
                res must include(
                  "The max number of WebSockets for session " +
                    "limit-test-producer-1 has been reached. Limit is 1"
                )
              }
            }
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
            producerId = producerId("json", topicCounter),
            instanceId = None,
            topicName = topicName
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

      "reject a new connection if the consumer already exists" taggedAs
        Retryable in plainJsonConsumerContext(
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
              " consumer with the same ID is already registered."

          val out = "/socket/out?" +
            s"clientId=json-test-$topicCounter" +
            s"&groupId=json-test-group-$topicCounter" +
            s"&topic=${ctx.topicName.value}" +
            s"&valType=${StringType.name}" +
            "&autoCommit=false"

          val probe1 = WSProbe()
          val probe2 = ctx.consumerProbe

          WS(out, probe1.flow) ~> ctx.route ~> check {
            isWebSocketUpgrade mustBe true
            // Make sure consumer socket 1 is ready and registered in session
            // FIXME: This test is really flaky!!!
            Thread.sleep((10 seconds).toMillis)

            WS(out, probe2.flow) ~> ctx.route ~> check {
              status mustBe BadRequest
              responseAs[String] must include(rejectionMsg)
            }
          }
        }

      "allow a consumer to reconnect if a connection is terminated" in
        plainJsonConsumerContext(
          topic = nextTopic,
          keyType = None,
          valType = StringType,
          partitions = 2
        ) { ctx =>
          val out = "/socket/out?" +
            s"clientId=json-test-$topicCounter" +
            s"&groupId=json-test-group-$topicCounter" +
            s"&topic=${ctx.topicName.value}" +
            s"&valType=${StringType.name}" +
            "&autoCommit=false"

          withEmbeddedServer(
            routes = ctx.route,
            completionWaitDuration = Some(10 seconds)
          ) { (host, port) =>
            // validate first request
            forAll(1 to 10) { _ =>
              assertWSRequest(host, port, out, numExpMsgs = 1)(initialDelay =
                2 seconds
              )
            }
          }
        }

      "reject new consumer connection if the client limit has been reached" in
        plainJsonConsumerContext(
          topic = nextTopic,
          keyType = None,
          valType = StringType,
          numMessages = 0,
          prePopulate = false
        ) { ctx =>
          val rejectionMsg =
            "The max number of WebSockets for session dummy has been reached." +
              " Limit is 1"

          val out = (cid: String) =>
            "/socket/out?" +
              s"clientId=json-test-$topicCounter$cid" +
              s"&groupId=dummy" +
              s"&topic=${ctx.topicName.value}" +
              s"&valType=${StringType.name}" +
              "&autoCommit=false"

          val probe1 = WSProbe()

          WS(out("a"), probe1.flow) ~> ctx.route ~> check {
            isWebSocketUpgrade mustBe true
            // Make sure consumer socket 1 is ready and registered in session
            Thread.sleep((4 seconds).toMillis)

            WS(out("b"), ctx.consumerProbe.flow) ~> ctx.route ~> check {
              status mustBe BadRequest
              responseAs[String] must include(rejectionMsg)
            }
          }
        }
    }
  }

}
