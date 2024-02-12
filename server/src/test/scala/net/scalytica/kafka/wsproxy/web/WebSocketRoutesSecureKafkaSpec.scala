package net.scalytica.kafka.wsproxy.web

import io.github.embeddedkafka.Codecs.stringDeserializer
import io.github.embeddedkafka.EmbeddedKafkaConfig
import net.scalytica.kafka.wsproxy.models.Formats.{JsonType, NoType, StringType}
import net.scalytica.kafka.wsproxy.web.SocketProtocol.JsonPayload
import net.scalytica.test.SharedAttributes.creds
import net.scalytica.test._
import org.apache.pekko.http.scaladsl.model.ContentTypes
import org.apache.pekko.http.scaladsl.model.StatusCodes.Unauthorized
import org.apache.pekko.http.scaladsl.model.headers.BasicHttpCredentials
import org.apache.pekko.http.scaladsl.server._
import org.apache.pekko.http.scaladsl.testkit.WSProbe
import org.scalatest.Inspectors.forAll
import org.scalatest.wordspec.AnyWordSpec

// scalastyle:off magic.number
class WebSocketRoutesSecureKafkaSpec
    extends AnyWordSpec
    with BaseWebSocketRoutesSpec
    with WsReusableProxyKafkaFixture
    with FlakyTests {

  override protected val testTopicPrefix: String   = "json-test-topic"
  override protected lazy val secureKafka: Boolean = true

  "Using JSON payloads with WebSockets" when {

    "kafka is secure and the server is unsecured" should {

      "be able to produce messages" in
        withProducerContext(nextTopic) { implicit ctx =>
          implicit val wsClient: WSProbe = ctx.producerProbe

          val messages = createJsonKeyValue(1)

          produceAndAssertJson(
            producerId = producerId("json", topicCounter),
            instanceId = None,
            topic = ctx.topicName.value,
            keyType = StringType,
            valType = StringType,
            routes = Route.seal(wsRouteFromProducerContext),
            messages = messages,
            kafkaCreds = Some(creds)
          )
        }

      "be able to consume messages" in
        withConsumerContext(
          topic = nextTopic,
          keyType = None,
          valType = StringType,
          numMessages = 10
        ) { implicit ctx =>
          implicit val kcfg: EmbeddedKafkaConfig = ctx.embeddedKafkaConfig

          val out = "/socket/out?" +
            s"clientId=json-test-$topicCounter" +
            s"&groupId=json-test-group-$topicCounter" +
            s"&topic=${ctx.topicName.value.value}" +
            s"&valType=${StringType.name}" +
            "&autoCommit=true"

          consumeFirstMessageFrom[String](
            ctx.topicName.value.value
          ) mustBe "bar-1"

          WS(out, ctx.consumerProbe.flow) ~>
            addKafkaCreds(creds) ~>
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

      "return a HTTP 401 when using wrong credentials to establish an" +
        " outbound connection to a secured cluster" in
        withConsumerContext(
          topic = nextTopic,
          keyType = Some(StringType),
          valType = StringType,
          numMessages = 0,
          prePopulate = false
        ) { implicit ctx =>
          val out = "/socket/out?" +
            s"clientId=json-test-$topicCounter" +
            s"&groupId=json-test-group-$topicCounter" +
            s"&topic=${ctx.topicName.value.value}" +
            s"&socketPayload=${JsonPayload.name}" +
            s"&keyType=${JsonType.name}" +
            s"&valType=${JsonType.name}" +
            "&autoCommit=false"

          val wrongCreds = addKafkaCreds(BasicHttpCredentials("bad", "user"))

          WS(out, ctx.consumerProbe.flow) ~>
            wrongCreds ~>
            Route.seal(wsRouteFromConsumerContext) ~>
            check {
              status mustBe Unauthorized
              contentType mustBe ContentTypes.`application/json`
            }
        }

      "return a HTTP 401 when using wrong credentials to establish an inbound" +
        " connection to a secured cluster" in
        withProducerContext(topic = nextTopic) { implicit ctx =>
          implicit val wsClient: WSProbe = ctx.producerProbe
          val baseUri = baseProducerUri(
            producerId = producerId("json", topicCounter),
            instanceId = None,
            topicName = ctx.topicName,
            payloadType = JsonPayload,
            keyType = NoType,
            valType = StringType
          )

          val wrongCreds = BasicHttpCredentials("bad", "user")

          inspectWebSocket(
            uri = baseUri,
            routes = Route.seal(wsRouteFromProducerContext),
            kafkaCreds = Some(wrongCreds)
          ) {
            status mustBe Unauthorized
            contentType mustBe ContentTypes.`application/json`
          }
        }
    }
  }

}
