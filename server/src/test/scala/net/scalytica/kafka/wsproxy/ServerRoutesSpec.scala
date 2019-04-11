package net.scalytica.kafka.wsproxy

import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server._
import akka.http.scaladsl.testkit.{ScalatestRouteTest, WSProbe}
import net.manub.embeddedkafka.schemaregistry._
import net.scalytica.test._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Minutes, Span}
import org.scalatest.{EitherValues, MustMatchers, WordSpec}

class ServerRoutesSpec
    extends WordSpec
    with MustMatchers
    with EitherValues
    with ScalaFutures
    with ScalatestRouteTest
    with WSProxySpecLike
    with EmbeddedKafka {

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(2, Minutes))

  case object TestRoutes extends ServerRoutes

  import TestRoutes.{serverErrorHandler, serverRejectionHandler}

  private[this] def newProducerKeyValueRecord() = {
    """{
      |  "key": {
      |    "value": "foo",
      |    "format": "string"
      |  },
      |  "value": {
      |    "value": "bar",
      |    "format": "string"
      |  }
      |}""".stripMargin
  }

  def produce(
      inPath: String,
      routes: Route
  )(implicit wsClient: WSProbe): Unit = {
    WS(inPath, wsClient.flow) ~> routes ~> check {
      isWebSocketUpgrade mustBe true

      wsClient.sendMessage(newProducerKeyValueRecord())
      wsClient.expectWsProducerResult("foobar")

      wsClient.sendCompletion()
      wsClient.expectCompletion()
    }
  }

  "The server routes" should {
    "return a 404 NotFound when requesting an invalid resource" in {
      implicit val cfg = defaultApplicationTestConfig
      val routes       = Route.seal(TestRoutes.routes)

      Get() ~> routes ~> check {
        status mustBe NotFound
        responseAs[String] mustBe "This is not the page you are looking for."
      }
    }

    "set up a WebSocket connection for producing messages" in
      withRunningKafkaOnFoundPort(embeddedKafkaConfig) { implicit kcfg =>
        implicit val wsCfg = applicationTestConfig(kcfg.kafkaPort)

        val routes            = Route.seal(TestRoutes.routes)
        implicit val wsClient = WSProbe()

        produce(
          "/socket/in?topic=foobar&keyType=string&valType=string",
          routes
        )
      }

    "set up a WebSocket connection for consuming messages" in
      withRunningKafkaOnFoundPort(embeddedKafkaConfig) { implicit kcfg =>
        implicit val wsCfg = applicationTestConfig(kcfg.kafkaPort)

        val routes                   = Route.seal(TestRoutes.routes)
        val producerProbe            = WSProbe()
        implicit val wsConsumerProbe = WSProbe()

        produce(
          "/socket/in?topic=foobar&keyType=string&valType=string",
          routes
        )(
          producerProbe
        )

        val outPath = "/socket/out?" +
          "clientId=test" +
          "&groupId=test-group" +
          "&topic=foobar" +
          "&keyType=string" +
          "&valType=string" +
          "&autoCommit=false"

        val record =
          """{
          |  "wsProxyMessageId": "foobar-2-12-1554402266846",
          |  "partition": 2,
          |  "offset": 12L,
          |  "key": {
          |    "value": "foo",
          |    "format": "string"
          |  },
          |  "value": {
          |    "value": "bar",
          |    "format": "string"
          |  }
          |}""".stripMargin

        import net.manub.embeddedkafka.Codecs.stringDeserializer

        val (rk, rv) = consumeFirstKeyedMessageFrom[String, String]("foobar")
        rk mustBe "foo"
        rv mustBe "bar"

        WS(outPath, wsConsumerProbe.flow) ~> routes ~> check {
          isWebSocketUpgrade mustBe true

          wsConsumerProbe.expectWsConsumerKeyValueResult[String, String](
            expectedTopic = "foobar",
            expectedKey = "foo",
            expectedValue = "bar"
          )

        }
      }
  }

}
