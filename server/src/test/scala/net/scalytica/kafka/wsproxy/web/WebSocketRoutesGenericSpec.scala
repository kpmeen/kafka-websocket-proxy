package net.scalytica.kafka.wsproxy.web

import akka.http.scaladsl.model.ContentTypes
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.headers.BasicHttpCredentials
import akka.http.scaladsl.server._
import akka.http.scaladsl.testkit.WSProbe
import net.scalytica.kafka.wsproxy.auth.AccessToken
import net.scalytica.kafka.wsproxy.models.Formats.{JsonType, NoType, StringType}
import net.scalytica.kafka.wsproxy.models.{
  TopicName,
  WsProducerId,
  WsProducerInstanceId
}
import net.scalytica.kafka.wsproxy.web.SocketProtocol._
import net.scalytica.test.FlakyTests
import org.scalatest.Inspectors.forAll
import org.scalatest.tagobjects.Retryable
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.duration._

// scalastyle:off magic.number
class WebSocketRoutesGenericSpec
    extends AnyWordSpec
    with BaseWebSocketRoutesSpec
    with FlakyTests {

  override protected val testTopicPrefix: String = "generic-test-topic"

  private[this] def shortProducerUri(
      pid: String,
      iid: Option[String],
      useExactlyOnce: Boolean = false
  )(
      implicit ctx: ProducerContext
  ) = {
    baseProducerUri(
      producerId = WsProducerId(pid),
      instanceId = iid.map(WsProducerInstanceId.apply),
      topicName = ctx.topicName,
      payloadType = JsonPayload,
      exactlyOnce = useExactlyOnce
    )
  }

  private[this] def assertInvalidTransactionalConfig(
      useProducerSessions: Boolean,
      useExactlyOnce: Boolean
  ) = {
    plainProducerContext(
      nextTopic,
      useProducerSessions = useProducerSessions,
      useExactlyOnce = useExactlyOnce
    ) { implicit ctx =>
      implicit val wsClient = ctx.producerProbe
      val uri = buildProducerUri(
        producerId = Some(producerId("producer", topicCounter)),
        instanceId = Some(WsProducerInstanceId("producer-a")),
        topicName = Some(ctx.topicName),
        transactional = Some(true)
      )

      assertProducerWS(wsRouteFromProducerContext, uri) {
        isWebSocketUpgrade mustBe false
        status mustBe BadRequest
        responseAs[String] must include(
          "Server is not configured to allow producer transactions"
        )
        contentType mustBe ContentTypes.`application/json`
      }
    }
  }

  "Using the WebSockets" when {

    "the server routes generic behavior" should {

      "reject producer connection when the required clientId is not set" in
        plainProducerContext(nextTopic) { implicit ctx =>
          assertRejectMissingProducerId()
        }

      "reject producer connection when sessions are enabled and instanceId " +
        "is not set" in
        plainProducerContext(nextTopic, useProducerSessions = true) {
          implicit ctx =>
            assertNoInstanceId(useSession = true)
        }

      "allow producer connection when sessions are enabled and instanceId " +
        "is not set" in
        plainProducerContext(nextTopic) { implicit ctx =>
          assertNoInstanceId(useSession = false)
        }

      "reject producer connection when the required topic is not set" in
        plainProducerContext(nextTopic) { implicit ctx =>
          assertRejectMissingTopicName()
        }

      "allow a producer to reconnect when sessions are not enabled" in
        plainProducerContext(nextTopic) { implicit ctx =>
          val in = shortProducerUri(s"json-test-$topicCounter", None)

          withEmbeddedServer(
            routes = wsRouteFromProducerContext,
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
              routes = wsRouteFromProducerContext,
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

            val route = wsRouteFromProducerContext

            withEmbeddedServer(
              routes = route,
              completionWaitDuration = Some(10 seconds)
            ) { (host, port) =>
              WS(in("instance-1"), wsClient.flow) ~> route ~> check {
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

            val route = wsRouteFromProducerContext

            WS(in("instance-1"), wsClient1.flow) ~> route ~> check {
              isWebSocketUpgrade mustBe true

              WS(in("instance-2"), wsClient2.flow) ~> route ~> check {
                isWebSocketUpgrade mustBe true

                // Make sure both sockets are ready and registered in session
                Thread.sleep((4 seconds).toMillis)

                WS(in("instance-3"), wsClient3.flow) ~> route ~> check {
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

            val route = wsRouteFromProducerContext

            WS(in("instance-1"), wsClient1.flow) ~> route ~> check {
              isWebSocketUpgrade mustBe true
              // Make sure consumer socket 1 is ready and registered in session
              Thread.sleep((4 seconds).toMillis)

              WS(in("instance-2"), wsClient2.flow) ~> route ~> check {
                status mustBe BadRequest
                val res = responseAs[String]
                res must include(
                  "The max number of WebSockets for session " +
                    "limit-test-producer-1 has been reached. Limit is 1"
                )
                contentType mustBe ContentTypes.`application/json`
              }
            }
        }

      // TODO: Implement test cases for exactly once producer semantics

      "reject exactly once producer connection when no instanceId" in
        plainProducerContext(
          nextTopic,
          useProducerSessions = true,
          useExactlyOnce = true
        ) { implicit ctx =>
          assertNoInstanceId(
            useSession = true,
            exactlyOnce = Some(true)
          )
        }

      "reject exactly once producer when transactions are disabled" in {
        assertInvalidTransactionalConfig(
          useProducerSessions = true,
          useExactlyOnce = false
        )
      }

      "reject exactly once producer when sessions are disabled" in {
        assertInvalidTransactionalConfig(
          useProducerSessions = false,
          useExactlyOnce = true
        )
      }

      "return HTTP 400 when attempting to produce to a non-existing topic" in
        plainProducerContext(nextTopic) { implicit ctx =>
          val topicName = TopicName("non-existing-topic")

          val uri = baseProducerUri(
            producerId = producerId("json", topicCounter),
            instanceId = None,
            topicName = topicName
          )

          WS(uri, ctx.producerProbe.flow) ~>
            Route.seal(wsRouteFromProducerContext) ~>
            check {
              status mustBe BadRequest
              contentType mustBe ContentTypes.`application/json`
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
        ) { implicit ctx =>
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

          val route = wsRouteFromConsumerContext

          val probe1 = WSProbe()
          val probe2 = ctx.consumerProbe

          WS(out, probe1.flow) ~> route ~> check {
            isWebSocketUpgrade mustBe true
            // Make sure consumer socket 1 is ready and registered in session
            // FIXME: This test is really flaky!!!
            Thread.sleep((10 seconds).toMillis)

            WS(out, probe2.flow) ~> route ~> check {
              status mustBe BadRequest
              responseAs[String] must include(rejectionMsg)
              contentType mustBe ContentTypes.`application/json`
            }
          }
        }

      "allow a consumer to reconnect if a connection is terminated" in
        plainJsonConsumerContext(
          topic = nextTopic,
          keyType = None,
          valType = StringType,
          partitions = 2
        ) { implicit ctx =>
          val out = "/socket/out?" +
            s"clientId=json-test-$topicCounter" +
            s"&groupId=json-test-group-$topicCounter" +
            s"&topic=${ctx.topicName.value}" +
            s"&valType=${StringType.name}" +
            "&autoCommit=false"

          withEmbeddedServer(
            routes = wsRouteFromConsumerContext,
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
        ) { implicit ctx =>
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

          val route = wsRouteFromConsumerContext

          val probe1 = WSProbe()

          WS(out("a"), probe1.flow) ~> route ~> check {
            isWebSocketUpgrade mustBe true
            // Make sure consumer socket 1 is ready and registered in session
            Thread.sleep((4 seconds).toMillis)

            WS(out("b"), ctx.consumerProbe.flow) ~> route ~> check {
              status mustBe BadRequest
              responseAs[String] must include(rejectionMsg)
              contentType mustBe ContentTypes.`application/json`
            }
          }
        }
    }

    "kafka is secure and the server is unsecured" should {

      "return a HTTP 401 when using wrong credentials to establish an" +
        " outbound connection to a secured cluster" in
        secureKafkaAvroConsumerContext(
          topic = nextTopic,
          keyType = Some(StringType),
          valType = StringType,
          numMessages = 0,
          prePopulate = false
        ) { implicit ctx =>
          val out = "/socket/out?" +
            s"clientId=json-test-$topicCounter" +
            s"&groupId=json-test-group-$topicCounter" +
            s"&topic=${ctx.topicName.value}" +
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
        secureKafkaClusterProducerContext(topic = nextTopic) { implicit ctx =>
          implicit val wsClient = ctx.producerProbe
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

    "kafka is secured and the proxy is secured using OpenID Connect" should {

      "return HTTP 401 when JWT token is invalid with OpenID enabled" in
        withOpenIdConnectServerAndClient(useJwtCreds = false) {
          case (_, _, _, cfg) =>
            secureServerProducerContext(
              topic = nextTopic,
              serverOpenIdCfg = Option(cfg)
            ) { implicit ctx =>
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
                routes = Route.seal(wsRouteFromProducerContext),
                creds = Some(token.bearerToken),
                kafkaCreds = Some(creds)
              ) {
                status mustBe Unauthorized
                contentType mustBe ContentTypes.`application/json`
              }
            }
        }

      "return HTTP 401 when JWT token is valid but Kafka creds are invalid" in
        withOpenIdConnectServerAndClient(useJwtCreds = true) {
          case (oidcHost, oidcPort, _, cfg) =>
            secureServerProducerContext(
              topic = nextTopic,
              serverOpenIdCfg = Option(cfg)
            ) { implicit ctx =>
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
                routes = Route.seal(wsRouteFromProducerContext),
                creds = Some(token.bearerToken)
              ) {
                status mustBe Unauthorized
                contentType mustBe ContentTypes.`application/json`
              }
            }
        }

      "return HTTP 503 when OpenID server is unavailable" in
        withUnavailableOpenIdConnectServerAndToken(useJwtCreds = false) {
          case (_, cfg, token) =>
            secureServerProducerContext(
              topic = nextTopic,
              serverOpenIdCfg = Option(cfg)
            ) { implicit ctx =>
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
                routes = Route.seal(wsRouteFromProducerContext),
                creds = Some(token.bearerToken),
                kafkaCreds = Some(creds)
              ) {
                status mustBe ServiceUnavailable
                contentType mustBe ContentTypes.`application/json`
              }
            }
        }
    }

  }

}
