package net.scalytica.kafka.wsproxy.web

import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.headers.BasicHttpCredentials
import akka.http.scaladsl.server._
import net.scalytica.kafka.wsproxy.models.Formats.{AvroType, NoType, StringType}
import net.scalytica.kafka.wsproxy.web.SocketProtocol.AvroPayload

// scalastyle:off magic.number
class WebSocketRoutesAvroBasicAuthSpec extends WebSocketRoutesAvroScaffolding {

  "Using Avro payloads with WebSockets" when {

    "kafka is secure and the server is unsecured" should {
      // scalastyle:off line.size.limit
      "return a HTTP 401 when using wrong credentials to establish an outbound connection to a secured cluster" in
        // scalastyle:on line.size.limit
        secureKafkaAvroConsumerContext(
          topic = nextTopic,
          keyType = Some(AvroType),
          valType = AvroType,
          numMessages = 0,
          prePopulate = false
        ) { ctx =>
          val out = "/socket/out?" +
            s"clientId=avro-test-$topicCounter" +
            s"&groupId=avro-test-group-$topicCounter" +
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
        secureKafkaClusterProducerContext(topic = nextTopic) { implicit ctx =>
          implicit val wsClient = ctx.producerProbe
          val baseUri = baseProducerUri(
            clientId = producerClientId("avro", topicCounter),
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
        secureKafkaClusterProducerContext(topic = nextTopic) { ctx =>
          implicit val wsClient = ctx.producerProbe

          val messages = createAvroProducerRecordNoneAvro(1)

          produceAndCheckAvro(
            clientId = producerClientId("avro", topicCounter),
            topic = ctx.topicName,
            routes = Route.seal(ctx.route),
            keyType = None,
            valType = AvroType,
            messages = messages,
            kafkaCreds = Some(creds)
          )
        }
    }
  }
}
