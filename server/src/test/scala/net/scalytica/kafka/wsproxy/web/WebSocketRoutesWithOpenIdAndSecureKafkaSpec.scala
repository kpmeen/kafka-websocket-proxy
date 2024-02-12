package net.scalytica.kafka.wsproxy.web

import net.scalytica.kafka.wsproxy.auth.AccessToken
import net.scalytica.kafka.wsproxy.config.Configuration.CustomJwtCfg
import net.scalytica.kafka.wsproxy.models.Formats.{JsonType, NoType, StringType}
import net.scalytica.kafka.wsproxy.web.SocketProtocol.JsonPayload
import net.scalytica.test.SharedAttributes.creds
import net.scalytica.test._
import org.apache.pekko.http.scaladsl.model.ContentTypes
import org.apache.pekko.http.scaladsl.model.StatusCodes.{ServiceUnavailable, Unauthorized}
import org.apache.pekko.http.scaladsl.server._
import org.apache.pekko.http.scaladsl.testkit.WSProbe
import org.scalatest.wordspec.AnyWordSpec

import java.net.URL

// scalastyle:off magic.number
class WebSocketRoutesWithOpenIdAndSecureKafkaSpec
    extends AnyWordSpec
    with BaseWebSocketRoutesSpec
    with WsReusableProxyKafkaFixture
    with FlakyTests {

  override protected val testTopicPrefix: String   = "json-test-topic"
  override protected lazy val secureKafka: Boolean = true

  "Using the WebSockets secured with OpenID" when {

    "kafka is secure" should {

      "allow producer connections with a valid JWT token" in
        withOpenIdServerProducerContext(topic = nextTopic) {
          case (_, oidcClient, prodCtx) =>
            implicit val ctx: ProducerContext = prodCtx
            implicit val wsClient: WSProbe    = ctx.producerProbe

            val token = oidcClient
              .generateToken(
                oidClientId,
                oidClientSecret,
                oidAudience,
                oidGrantTpe
              )
              .futureValue
              .value

            val messages = createJsonValue(1)

            produceAndAssertJson(
              producerId = producerId("json", topicCounter),
              instanceId = None,
              topic = ctx.topicName.value,
              routes = Route.seal(wsRouteFromProducerContext),
              keyType = NoType,
              valType = JsonType,
              messages = messages,
              creds = Some(token.bearerToken),
              kafkaCreds = Some(creds)
            )
        }

      "allow connections with a valid JWT token containing Kafka credentials" in
        withOpenIdServerProducerContext(topic = nextTopic, useJwtCreds = true) {
          case (_, oidcClient, prodCtx) =>
            implicit val ctx: ProducerContext = prodCtx
            implicit val wsClient: WSProbe    = ctx.producerProbe

            val token = oidcClient
              .generateToken(
                oidClientId,
                oidClientSecret,
                oidAudience,
                oidGrantTpe
              )
              .futureValue
              .value

            val messages = createJsonValue(1)

            produceAndAssertJson(
              producerId = producerId("json", topicCounter),
              instanceId = None,
              topic = ctx.topicName.value,
              routes = Route.seal(wsRouteFromProducerContext),
              keyType = NoType,
              valType = JsonType,
              messages = messages,
              creds = Some(token.bearerToken)
            )
        }

      "allow connections with a valid JWT token containing Kafka credentials " +
        "when using a custom JWT config" in
        withOpenIdServerProducerContext(
          topic = nextTopic,
          useJwtCreds = true,
          customJwtCfg = Some(
            CustomJwtCfg(
              jwtKafkaUsernameKey = jwtKafkaCredsUsernameKey,
              jwtKafkaPasswordKey = jwtKafkaCredsPasswordKey
            )
          )
        ) { case (_, oidcClient, prodCtx) =>
          implicit val ctx: ProducerContext = prodCtx
          implicit val wsClient: WSProbe    = ctx.producerProbe

          val token = oidcClient
            .generateToken(
              oidClientId,
              oidClientSecret,
              oidAudience,
              oidGrantTpe
            )
            .futureValue
            .value

          val messages = createJsonValue(1)

          produceAndAssertJson(
            producerId = producerId("json", topicCounter),
            instanceId = None,
            topic = ctx.topicName.value,
            routes = Route.seal(wsRouteFromProducerContext),
            keyType = NoType,
            valType = JsonType,
            messages = messages,
            creds = Some(token.bearerToken),
            kafkaCreds = None
          )
        }

      "return HTTP 401 when JWT token is invalid with OpenID enabled" in
        withOpenIdServerProducerContext(
          topic = nextTopic
        ) { case (_, _, ctx) =>
          implicit val prodCtx: ProducerContext = ctx
          implicit val wsClient: WSProbe        = ctx.producerProbe

          val token = AccessToken("Bearer", "foo.bar.baz", 3600L, None)

          val baseUri = baseProducerUri(
            producerId = producerId("json", topicCounter),
            instanceId = None,
            topicName = ctx.topicName,
            payloadType = JsonPayload,
            keyType = NoType,
            valType = StringType
          )

          inspectWebSocket(
            uri = baseUri,
            routes = Route.seal(wsRouteFromProducerContext),
            creds = Some(token.bearerToken),
            kafkaCreds = Some(creds)
          ) {
            status mustBe Unauthorized
            contentType mustBe ContentTypes.`application/json`
          }
        }

      "return HTTP 401 when JWT token is valid but Kafka creds are invalid" in
        withOpenIdServerProducerContext(topic = nextTopic, useJwtCreds = true) {
          case (oidcCfg, _, ctx) =>
            implicit val prodCtx: ProducerContext = ctx
            implicit val wsClient: WSProbe        = ctx.producerProbe

            val url   = new URL(oidcCfg.wellKnownUrl.value)
            val token = invalidAccessToken(url.getHost, url.getPort)

            val baseUri = baseProducerUri(
              producerId = producerId("json", topicCounter),
              instanceId = None,
              topicName = ctx.topicName,
              payloadType = JsonPayload,
              keyType = NoType,
              valType = StringType
            )

            inspectWebSocket(
              uri = baseUri,
              routes = Route.seal(wsRouteFromProducerContext),
              creds = Some(token.bearerToken)
            ) {
              status mustBe Unauthorized
              contentType mustBe ContentTypes.`application/json`
            }
        }

      "return HTTP 503 when OpenID server is unavailable" in
        withUnavailableOpenIdProducerContext(nextTopic) {
          case (_, oidcToken, prodCtx) =>
            implicit val ctx: ProducerContext = prodCtx
            implicit val wsClient: WSProbe    = ctx.producerProbe

            val baseUri = baseProducerUri(
              producerId = producerId("json", topicCounter),
              instanceId = None,
              topicName = ctx.topicName,
              payloadType = JsonPayload,
              keyType = NoType,
              valType = StringType
            )

            inspectWebSocket(
              uri = baseUri,
              routes = Route.seal(wsRouteFromProducerContext),
              creds = Some(oidcToken.bearerToken),
              kafkaCreds = Some(creds)
            ) {
              status mustBe ServiceUnavailable
              contentType mustBe ContentTypes.`application/json`
            }
        }
    }
  }

}
