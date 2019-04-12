package net.scalytica.kafka.wsproxy

import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server._
import akka.http.scaladsl.testkit.{ScalatestRouteTest, WSProbe}
import net.manub.embeddedkafka.schemaregistry._
import net.scalytica.kafka.wsproxy.models.Formats.{
  FormatType,
  NoType,
  StringType
}
import net.scalytica.test._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Minutes, Span}
import org.scalatest.{EitherValues, MustMatchers, WordSpec}
import org.scalatest.Inspectors.forAll

// scalastyle:off magic.number
class ServerRoutesSpec
    extends WordSpec
    with MustMatchers
    with EitherValues
    with ScalaFutures
    with ScalatestRouteTest
    with WSProxySpecLike
    with TestDataGenerators
    with EmbeddedKafka {

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(2, Minutes))

  case object TestRoutes extends ServerRoutes

  import TestRoutes.{serverErrorHandler, serverRejectionHandler}

  def produce(
      topic: String,
      keyType: FormatType,
      valType: FormatType,
      routes: Route,
      messages: Seq[String]
  )(implicit wsClient: WSProbe): Unit = {
    val baseUri =
      s"/socket/in?topic=$topic&valType=${valType.name}"

    val uri =
      if (keyType != NoType) baseUri + s"&keyType=${keyType.name}" else baseUri

    WS(uri, wsClient.flow) ~> routes ~> check {
      isWebSocketUpgrade mustBe true

      forAll(messages) { msg =>
        wsClient.sendMessage(msg)
        wsClient.expectWsProducerResult(topic)
      }
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

    "set up a WebSocket connection for producing key value messages" in
      withRunningKafkaOnFoundPort(embeddedKafkaConfig) { implicit kcfg =>
        implicit val wsCfg = applicationTestConfig(kcfg.kafkaPort)

        implicit val wsClient = WSProbe()
        val routes            = Route.seal(TestRoutes.routes)
        val messages          = producerKeyValueJson(1)

        produce("test-topic-1", StringType, StringType, routes, messages)
      }

    "set up a WebSocket connection for producing value messages" in
      withRunningKafkaOnFoundPort(embeddedKafkaConfig) { implicit kcfg =>
        implicit val wsCfg = applicationTestConfig(kcfg.kafkaPort)

        implicit val wsClient = WSProbe()
        val routes            = Route.seal(TestRoutes.routes)
        val messages          = producerValueJson(1)

        produce("test-topic-2", NoType, StringType, routes, messages)
      }

    "set up a WebSocket connection for consuming key value messages" in
      withRunningKafkaOnFoundPort(embeddedKafkaConfig) { implicit kcfg =>
        implicit val wsCfg = applicationTestConfig(kcfg.kafkaPort)

        implicit val wsConsumerProbe = WSProbe()
        val producerProbe            = WSProbe()
        val routes                   = Route.seal(TestRoutes.routes)
        val topic                    = "test-topic-3"

        produce(
          topic = topic,
          keyType = StringType,
          valType = StringType,
          routes = routes,
          messages = producerKeyValueJson(10)
        )(producerProbe)

        val outPath = "/socket/out?" +
          "clientId=test-3" +
          "&groupId=test-group-3" +
          s"&topic=$topic" +
          "&keyType=string" +
          "&valType=string" +
          "&autoCommit=false"

        import net.manub.embeddedkafka.Codecs.stringDeserializer

        val (rk, rv) = consumeFirstKeyedMessageFrom[String, String](topic)
        rk mustBe "foo-1"
        rv mustBe "bar-1"

        WS(outPath, wsConsumerProbe.flow) ~> routes ~> check {
          isWebSocketUpgrade mustBe true

          forAll(1 to 10) { i =>
            wsConsumerProbe.expectWsConsumerKeyValueResult[String, String](
              expectedTopic = topic,
              expectedKey = s"foo-$i",
              expectedValue = s"bar-$i"
            )
          }

        }
      }

    "set up a WebSocket connection for consuming value messages" in
      withRunningKafkaOnFoundPort(embeddedKafkaConfig) { implicit kcfg =>
        implicit val wsCfg = applicationTestConfig(kcfg.kafkaPort)

        implicit val wsConsumerProbe = WSProbe()
        val producerProbe            = WSProbe()
        val routes                   = Route.seal(TestRoutes.routes)
        val topic                    = "test-topic-4"

        produce(
          topic = topic,
          keyType = NoType,
          valType = StringType,
          routes = routes,
          messages = producerValueJson(10)
        )(producerProbe)

        val outPath = "/socket/out?" +
          "clientId=test-4" +
          "&groupId=test-group-4" +
          s"&topic=$topic" +
          "&valType=string" +
          "&autoCommit=false"

        import net.manub.embeddedkafka.Codecs.stringDeserializer

        consumeFirstMessageFrom[String](topic) mustBe "bar-1"

        WS(outPath, wsConsumerProbe.flow) ~> routes ~> check {
          isWebSocketUpgrade mustBe true

          forAll(1 to 10) { i =>
            wsConsumerProbe.expectWsConsumerValueResult[String](
              expectedTopic = topic,
              expectedValue = s"bar-$i"
            )
          }

        }
      }
  }

}
