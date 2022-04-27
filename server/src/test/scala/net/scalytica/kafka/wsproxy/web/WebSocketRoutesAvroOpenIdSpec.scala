package net.scalytica.kafka.wsproxy.web

import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server._
import net.scalytica.kafka.wsproxy.auth.AccessToken
import net.scalytica.kafka.wsproxy.config.Configuration.CustomJwtCfg
import net.scalytica.kafka.wsproxy.models.Formats.{AvroType, NoType, StringType}
import net.scalytica.kafka.wsproxy.web.SocketProtocol.AvroPayload

// scalastyle:off magic.number
class WebSocketRoutesAvroOpenIdSpec extends WebSocketRoutesAvroScaffolding {

  "Using Avro payloads with WebSockets" when {

    "kafka is secure and the server is secured with OpenID Connect" should {

      "allow connections with a valid bearer token when OpenID is enabled" in
        withOpenIdConnectServerAndToken(useJwtKafkaCreds = false) {
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

      "allow connections with a valid bearer token including " +
        "Kafka credentials when OpenID is enabled" in
        withOpenIdConnectServerAndToken(useJwtKafkaCreds = true) {
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

      "return HTTP 401 when bearer token is invalid with OpenID enabled" in
        withOpenIdConnectServerAndClient(useJwtKafkaCreds = false) {
          case (_, _, _, cfg) =>
            secureServerProducerContext(
              topic = nextTopic,
              serverOpenIdCfg = Option(cfg)
            ) { ctx =>
              implicit val wsClient = ctx.producerProbe

              val token = AccessToken("Bearer", "foo.bar.baz", 3600L, None)

              val baseUri = baseProducerUri(
                producerId = producerId("avro", topicCounter),
                instanceId = None,
                topicName = ctx.topicName,
                payloadType = AvroPayload,
                keyType = NoType,
                valType = StringType
              )

              inspectWebSocket(
                uri = baseUri,
                routes = Route.seal(ctx.route),
                creds = Some(token.bearerToken),
                kafkaCreds = Some(creds)
              ) {
                status mustBe Unauthorized
              }
            }
        }

      "return HTTP 401 when bearer token is valid but JWT keys for Kafka " +
        "creds are incorrect with OpenID enabled" in
        withOpenIdConnectServerAndClient(useJwtKafkaCreds = true) {
          case (oidcHost, oidcPort, _, cfg) =>
            secureServerProducerContext(
              topic = nextTopic,
              serverOpenIdCfg = Option(cfg)
            ) { ctx =>
              implicit val wsClient = ctx.producerProbe

              val token = invalidAccessToken(oidcHost, oidcPort)

              val baseUri = baseProducerUri(
                producerId = producerId("avro", topicCounter),
                instanceId = None,
                topicName = ctx.topicName,
                payloadType = AvroPayload,
                keyType = NoType,
                valType = StringType
              )

              inspectWebSocket(
                uri = baseUri,
                routes = Route.seal(ctx.route),
                creds = Some(token.bearerToken)
              ) {
                status mustBe Unauthorized
              }
            }
        }

      "return HTTP 503 when OpenID server is unavailable" in
        withUnavailableOpenIdConnectServerAndToken(useJwtKafkaCreds = false) {
          case (_, cfg, token) =>
            secureServerProducerContext(
              topic = nextTopic,
              serverOpenIdCfg = Option(cfg)
            ) { ctx =>
              implicit val wsClient = ctx.producerProbe

              val baseUri = baseProducerUri(
                producerId = producerId("avro", topicCounter),
                instanceId = None,
                topicName = ctx.topicName,
                payloadType = AvroPayload,
                keyType = NoType,
                valType = StringType
              )

              inspectWebSocket(
                uri = baseUri,
                routes = Route.seal(ctx.route),
                creds = Some(token.bearerToken),
                kafkaCreds = Some(creds)
              ) {
                status mustBe ServiceUnavailable
              }
            }
        }

      "allow connections with a valid bearer token that contains the kafka " +
        "credentials when OpenID is enabled" in
        withOpenIdConnectServerAndToken(useJwtKafkaCreds = true) {
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
