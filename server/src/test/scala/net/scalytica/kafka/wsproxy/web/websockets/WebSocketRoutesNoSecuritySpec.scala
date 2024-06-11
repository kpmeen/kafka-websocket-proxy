package net.scalytica.kafka.wsproxy.web

import org.apache.pekko.http.scaladsl.server._
import io.github.embeddedkafka.Codecs.stringDeserializer
import io.github.embeddedkafka.EmbeddedKafkaConfig
import net.scalytica.kafka.wsproxy.models.Formats.{NoType, StringType}
import net.scalytica.kafka.wsproxy.models.ReadCommitted
import net.scalytica.test._
import org.apache.pekko.http.scaladsl.testkit.WSProbe
import org.scalatest.Inspectors.forAll
import org.scalatest.wordspec.AnyWordSpec

// scalastyle:off magic.number
class WebSocketRoutesNoSecuritySpec
    extends AnyWordSpec
    with BaseWebSocketRoutesSpec
    with WsReusableProxyKafkaFixture
    with FlakyTests {

  override protected val testTopicPrefix: String = "json-test-topic"

  "Using JSON payloads with WebSockets" when {

    "kafka and server are both unsecured" should {

      "produce messages with key and value" in
        withProducerContext(nextTopic) { implicit ctx =>
          implicit val wsClient: WSProbe = ctx.producerProbe

          val msgs = createJsonKeyValue(1)

          produceAndAssertJson(
            producerId = producerId("json", topicCounter),
            instanceId = None,
            topic = ctx.topicName.value,
            keyType = StringType,
            valType = StringType,
            routes = Route.seal(wsRouteFromProducerContext),
            messages = msgs
          )
        }

      "produce messages with String value" in withProducerContext(nextTopic) {
        implicit ctx =>
          implicit val wsClient: WSProbe = ctx.producerProbe

          val msgs = createJsonValue(1)

          produceAndAssertJson(
            producerId = producerId("json", topicCounter),
            instanceId = None,
            topic = ctx.topicName.value,
            keyType = NoType,
            valType = StringType,
            routes = Route.seal(wsRouteFromProducerContext),
            messages = msgs
          )
      }

      "produce messages with headers, key and value" in
        withProducerContext(nextTopic) { implicit ctx =>
          implicit val wsClient: WSProbe         = ctx.producerProbe
          implicit val kcfg: EmbeddedKafkaConfig = ctx.embeddedKafkaConfig

          val msgs = createJsonKeyValue(1, withHeaders = true)

          produceAndAssertJson(
            producerId = producerId("json", topicCounter),
            instanceId = None,
            topic = ctx.topicName.value,
            keyType = StringType,
            valType = StringType,
            routes = Route.seal(wsRouteFromProducerContext),
            messages = msgs
          )

          // validate the topic contents
          val (k, v) =
            consumeFirstKeyedMessageFrom[String, String](
              ctx.topicName.value.value
            )
          k mustBe "foo-1"
          v mustBe "bar-1"
        }

      "produce messages with headers and values" in
        withProducerContext(nextTopic) { implicit ctx =>
          implicit val wsClient: WSProbe         = ctx.producerProbe
          implicit val kcfg: EmbeddedKafkaConfig = ctx.embeddedKafkaConfig

          val msgs = createJsonValue(1, withHeaders = true)

          produceAndAssertJson(
            producerId = producerId("json", topicCounter),
            instanceId = None,
            topic = ctx.topicName.value,
            keyType = NoType,
            valType = StringType,
            routes = Route.seal(wsRouteFromProducerContext),
            messages = msgs
          )

          // validate the topic contents
          consumeFirstMessageFrom[String](
            ctx.topicName.value.value
          ) mustBe "bar-1"
        }

      "produce messages with headers, key, value and message ID" in
        withProducerContext(nextTopic) { implicit ctx =>
          implicit val wsClient: WSProbe         = ctx.producerProbe
          implicit val kcfg: EmbeddedKafkaConfig = ctx.embeddedKafkaConfig

          val messages =
            createJsonKeyValue(1, withHeaders = true, withMessageId = true)

          produceAndAssertJson(
            producerId = producerId("json", topicCounter),
            instanceId = None,
            topic = ctx.topicName.value,
            keyType = StringType,
            valType = StringType,
            routes = Route.seal(wsRouteFromProducerContext),
            messages = messages,
            validateMessageId = true
          )

          // validate the topic contents
          val (k, v) =
            consumeFirstKeyedMessageFrom[String, String](
              ctx.topicName.value.value
            )
          k mustBe "foo-1"
          v mustBe "bar-1"
        }

      "produce messages using exactly once semantics" in
        withProducerContext(
          nextTopic,
          useProducerSessions = true,
          useExactlyOnce = true
        ) { implicit ctx =>
          implicit val wsClient: WSProbe = ctx.producerProbe
          implicit val kcfg: EmbeddedKafkaConfig =
            ctx.embeddedKafkaConfig.withConsumerReadIsolation(ReadCommitted)

          val messages =
            createJsonKeyValue(1, withHeaders = true, withMessageId = true)

          produceAndAssertJson(
            producerId = producerId("json", topicCounter),
            instanceId = Option(instanceId("instance-1")),
            topic = ctx.topicName.value,
            keyType = StringType,
            valType = StringType,
            routes = Route.seal(wsRouteFromProducerContext),
            messages = messages,
            validateMessageId = true,
            exactlyOnce = true
          )

          // validate the topic contents
          val (k, v) =
            consumeFirstKeyedMessageFrom[String, String](
              ctx.topicName.value.value
            )
          k mustBe "foo-1"
          v mustBe "bar-1"
        }

      "consume messages with key and value" in
        withConsumerContext(topic = nextTopic, numMessages = 10) {
          implicit ctx =>
            implicit val kcfg: EmbeddedKafkaConfig = ctx.embeddedKafkaConfig

            val out = "/socket/out?" +
              s"clientId=json-test-$topicCounter" +
              s"&groupId=json-test-group-$topicCounter" +
              s"&topic=${ctx.topicName.value.value}" +
              s"&keyType=${StringType.name}" +
              s"&valType=${StringType.name}" +
              "&autoCommit=false"

            // validate the topic contents
            val res =
              consumeNumberKeyedMessagesFrom[String, String](
                topic = ctx.topicName.value.value,
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
                      expectedTopic = ctx.topicName.value,
                      expectedKey = s"foo-$i",
                      expectedValue = s"bar-$i"
                    )
                }
              }
        }

      "consume messages with key, value and headers" in
        withConsumerContext(
          topic = nextTopic,
          numMessages = 10,
          withHeaders = true
        ) { implicit ctx =>
          implicit val kcfg: EmbeddedKafkaConfig = ctx.embeddedKafkaConfig

          val out = "/socket/out?" +
            s"clientId=json-test-$topicCounter" +
            s"&groupId=json-test-group-$topicCounter" +
            s"&topic=${ctx.topicName.value.value}" +
            s"&keyType=${StringType.name}" +
            s"&valType=${StringType.name}" +
            "&autoCommit=false"

          // validate the topic contents
          val res =
            consumeNumberKeyedMessagesFrom[String, String](
              topic = ctx.topicName.value.value,
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
                    expectedTopic = ctx.topicName.value,
                    expectedKey = s"foo-$i",
                    expectedValue = s"bar-$i",
                    expectHeaders = true
                  )
              }
            }
        }

      "consume messages with value" in withConsumerContext(
        topic = nextTopic,
        keyType = None,
        numMessages = 10
      ) { implicit ctx =>
        implicit val kcfg: EmbeddedKafkaConfig = ctx.embeddedKafkaConfig

        val out = "/socket/out?" +
          s"clientId=json-test-$topicCounter" +
          s"&groupId=json-test-group-$topicCounter" +
          s"&topic=${ctx.topicName.value.value}" +
          s"&valType=${StringType.name}" +
          "&autoCommit=false"

        consumeFirstMessageFrom[String](
          ctx.topicName.value.value
        ) mustBe "bar-1"

        WS(out, ctx.consumerProbe.flow) ~>
          wsRouteFromConsumerContext ~>
          check {
            isWebSocketUpgrade mustBe true

            forAll(1 to 10) { i =>
              ctx.consumerProbe.expectWsConsumerValueResultJson[String](
                expectedTopic = ctx.topicName.value,
                expectedValue = s"bar-$i"
              )
            }
          }
      }
    }
  }

}
