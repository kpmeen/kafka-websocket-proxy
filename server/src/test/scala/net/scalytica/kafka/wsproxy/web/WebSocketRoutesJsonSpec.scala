package net.scalytica.kafka.wsproxy.web

import akka.http.scaladsl.server._
import io.github.embeddedkafka.Codecs.stringDeserializer
import net.scalytica.kafka.wsproxy.models.Formats.{NoType, StringType}
import net.scalytica.test._
import org.scalatest.Inspectors.forAll
import org.scalatest.wordspec.AnyWordSpec

// scalastyle:off magic.number
class WebSocketRoutesJsonSpec
    extends AnyWordSpec
    with BaseWebSocketRoutesSpec
    with FlakyTests {

  override protected val testTopicPrefix: String = "json-test-topic"

  "Using JSON payloads with WebSockets" when {

    "the server routes" should {

      "produce messages with String key and value" in
        plainProducerContext(nextTopic) { implicit ctx =>
          implicit val wsClient = ctx.producerProbe

          val msgs = createJsonKeyValue(1)

          produceAndAssertJson(
            producerId = producerId("json", topicCounter),
            instanceId = None,
            topic = ctx.topicName,
            keyType = StringType,
            valType = StringType,
            routes = Route.seal(wsRouteFromProducerContext),
            messages = msgs
          )
        }

      "produce messages with String value" in
        plainProducerContext(nextTopic) { implicit ctx =>
          implicit val wsClient = ctx.producerProbe

          val msgs = createJsonValue(1)

          produceAndAssertJson(
            producerId = producerId("json", topicCounter),
            instanceId = None,
            topic = ctx.topicName,
            keyType = NoType,
            valType = StringType,
            routes = Route.seal(wsRouteFromProducerContext),
            messages = msgs
          )
        }

      "produce messages with headers and String key and value" in
        plainProducerContext(nextTopic) { implicit ctx =>
          implicit val wsClient = ctx.producerProbe
          implicit val kcfg     = ctx.embeddedKafkaConfig

          val msgs = createJsonKeyValue(1, withHeaders = true)

          produceAndAssertJson(
            producerId = producerId("json", topicCounter),
            instanceId = None,
            topic = ctx.topicName,
            keyType = StringType,
            valType = StringType,
            routes = Route.seal(wsRouteFromProducerContext),
            messages = msgs
          )

          // validate the topic contents
          val (k, v) =
            consumeFirstKeyedMessageFrom[String, String](ctx.topicName.value)
          k mustBe "foo-1"
          v mustBe "bar-1"
        }

      "produce messages with headers and String values" in
        plainProducerContext(nextTopic) { implicit ctx =>
          implicit val wsClient = ctx.producerProbe
          implicit val kcfg     = ctx.embeddedKafkaConfig

          val msgs = createJsonValue(1, withHeaders = true)

          produceAndAssertJson(
            producerId = producerId("json", topicCounter),
            instanceId = None,
            topic = ctx.topicName,
            keyType = NoType,
            valType = StringType,
            routes = Route.seal(wsRouteFromProducerContext),
            messages = msgs
          )

          // validate the topic contents
          consumeFirstMessageFrom[String](ctx.topicName.value) mustBe "bar-1"
        }

      "produce messages with headers, String key and value and message ID" in
        plainProducerContext(nextTopic) { implicit ctx =>
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
            routes = Route.seal(wsRouteFromProducerContext),
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
        ) { implicit ctx =>
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
          WS(out, ctx.consumerProbe.flow) ~>
            wsRouteFromConsumerContext ~>
            check {
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
        ) { implicit ctx =>
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
          WS(out, ctx.consumerProbe.flow) ~>
            wsRouteFromConsumerContext ~>
            check {
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
        ) { implicit ctx =>
          implicit val kcfg = ctx.embeddedKafkaConfig

          val out = "/socket/out?" +
            s"clientId=json-test-$topicCounter" +
            s"&groupId=json-test-group-$topicCounter" +
            s"&topic=${ctx.topicName.value}" +
            s"&valType=${StringType.name}" +
            "&autoCommit=false"

          consumeFirstMessageFrom[String](ctx.topicName.value) mustBe "bar-1"

          WS(out, ctx.consumerProbe.flow) ~>
            wsRouteFromConsumerContext ~>
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
    }

    "kafka is secure and the server is unsecured" should {

      "be able to produce messages" in
        secureKafkaClusterProducerContext(topic = nextTopic) { implicit ctx =>
          implicit val wsClient = ctx.producerProbe

          val messages = createJsonKeyValue(1)

          produceAndAssertJson(
            producerId = producerId("json", topicCounter),
            instanceId = None,
            topic = ctx.topicName,
            keyType = StringType,
            valType = StringType,
            routes = Route.seal(wsRouteFromProducerContext),
            messages = messages,
            kafkaCreds = Some(creds)
          )
        }

      "be able to consume messages" in
        secureKafkaJsonConsumerContext(
          topic = nextTopic,
          keyType = None,
          valType = StringType,
          numMessages = 10
        ) { implicit ctx =>
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
            wsRouteFromConsumerContext ~>
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
    }
  }

}
