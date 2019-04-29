package net.scalytica.kafka.wsproxy

import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server._
import akka.http.scaladsl.testkit.WSProbe
import net.manub.embeddedkafka.schemaregistry._
import net.scalytica.kafka.wsproxy.SocketProtocol.AvroPayload
import net.scalytica.kafka.wsproxy.avro.SchemaTypes.{
  AvroCommit,
  AvroConsumerRecord,
  AvroProducerRecord,
  AvroProducerResult
}
import net.scalytica.kafka.wsproxy.models.Formats.{AvroType, NoType, StringType}
import net.scalytica.test._
import net.scalytica.test.TestTypes._
import org.scalatest.Inspectors.forAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Minutes, Span}
import org.scalatest.{EitherValues, WordSpec}

import scala.concurrent.duration._

// scalastyle:off magic.number
class ServerRoutesSpec
    extends WordSpec
    with EitherValues
    with ScalaFutures
    with WSProxySpecLike
    with TestDataGenerators
    with EmbeddedKafka {

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(2, Minutes))

  case object TestRoutes extends ServerRoutes

  import TestRoutes.{serverErrorHandler, serverRejectionHandler}

  "The server routes" should {
    "return a 404 NotFound when requesting an invalid resource" in {
      implicit val cfg = defaultApplicationTestConfig
      val routes       = Route.seal(TestRoutes.routes)

      Get() ~> routes ~> check {
        status mustBe NotFound
        responseAs[String] mustBe "{\"message\":\"This is not the page you are looking for.\"}"
      }
    }

    "return the Avro schema for producer records" in {
      implicit val cfg = defaultApplicationTestConfig
      val routes       = Route.seal(TestRoutes.routes)

      Get("/schemas/avro/producer/record") ~> routes ~> check {
        status mustBe OK
        responseAs[String] mustBe AvroProducerRecord.schemaFor.schema
          .toString(true)
      }
    }

    "return the Avro schema for producer results" in {
      implicit val cfg = defaultApplicationTestConfig
      val routes       = Route.seal(TestRoutes.routes)

      Get("/schemas/avro/producer/result") ~> routes ~> check {
        status mustBe OK
        responseAs[String] mustBe AvroProducerResult.schemaFor.schema
          .toString(true)
      }
    }

    "return the Avro schema for consumer record" in {
      implicit val cfg = defaultApplicationTestConfig
      val routes       = Route.seal(TestRoutes.routes)

      Get("/schemas/avro/consumer/record") ~> routes ~> check {
        status mustBe OK
        responseAs[String] mustBe AvroConsumerRecord.schemaFor.schema
          .toString(true)
      }
    }

    "return the Avro schema for consumer commit" in {
      implicit val cfg = defaultApplicationTestConfig
      val routes       = Route.seal(TestRoutes.routes)

      Get("/schemas/avro/consumer/commit") ~> routes ~> check {
        status mustBe OK
        responseAs[String] mustBe AvroCommit.schemaFor.schema.toString(true)
      }
    }

    "set up a WebSocket connection for producing JSON key value messages" in
      withRunningKafkaOnFoundPort(embeddedKafkaConfig) { implicit kcfg =>
        implicit val wsCfg = applicationTestConfig(kcfg.kafkaPort)

        implicit val wsClient = WSProbe()
        val routes            = Route.seal(TestRoutes.routes)
        val messages          = producerKeyValueJson(1)

        produceJson("test-topic-1", StringType, StringType, routes, messages)
      }

    "set up a WebSocket connection for producing JSON value messages" in
      withRunningKafkaOnFoundPort(embeddedKafkaConfig) { implicit kcfg =>
        implicit val wsCfg = applicationTestConfig(kcfg.kafkaPort)

        implicit val wsClient = WSProbe()
        val routes            = Route.seal(TestRoutes.routes)
        val messages          = producerValueJson(1)

        produceJson("test-topic-2", NoType, StringType, routes, messages)
      }

    "set up a WebSocket connection for consuming JSON key value messages" in
      withRunningKafkaOnFoundPort(embeddedKafkaConfig) { implicit kcfg =>
        implicit val wsCfg = applicationTestConfig(kcfg.kafkaPort)

        implicit val wsConsumerProbe = WSProbe()
        val producerProbe            = WSProbe()
        val routes                   = Route.seal(TestRoutes.routes)
        val topic                    = "test-topic-3"

        produceJson(
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
            wsConsumerProbe.expectWsConsumerKeyValueResultJson[String, String](
              expectedTopic = topic,
              expectedKey = s"foo-$i",
              expectedValue = s"bar-$i"
            )
          }

        }
      }

    "set up a WebSocket connection for consuming JSON value messages" in
      withRunningKafkaOnFoundPort(embeddedKafkaConfig) { implicit kcfg =>
        implicit val wsCfg = applicationTestConfig(kcfg.kafkaPort)

        implicit val wsConsumerProbe = WSProbe()
        val producerProbe            = WSProbe()
        val routes                   = Route.seal(TestRoutes.routes)
        val topic                    = "test-topic-4"

        produceJson(
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
            wsConsumerProbe.expectWsConsumerValueResultJson[String](
              expectedTopic = topic,
              expectedValue = s"bar-$i"
            )
          }

        }
      }

    "set up a WebSocket connection for producing Avro key value messages" in
      withRunningKafkaOnFoundPort(embeddedKafkaConfig) { implicit kcfg =>
        implicit val wsCfg =
          applicationTestConfig(kcfg.kafkaPort, Option(kcfg.schemaRegistryPort))

        implicit val wsClient = WSProbe()
        val routes            = Route.seal(TestRoutes.routes)
        val messages          = producerKeyValueAvro(1)

        produceAvro("test-topic-5", routes, Some(AvroType), messages)
      }

    "set up a WebSocket connection for producing Avro value messages" in
      withRunningKafkaOnFoundPort(embeddedKafkaConfig) { implicit kcfg =>
        implicit val wsCfg =
          applicationTestConfig(kcfg.kafkaPort, Option(kcfg.schemaRegistryPort))

        implicit val wsClient = WSProbe()
        val routes            = Route.seal(TestRoutes.routes)
        val messages          = producerValueAvro(1)

        produceAvro("test-topic-6", routes, None, messages)
      }

    "set up a WebSocket connection for consuming Avro key value messages" in
      withRunningKafkaOnFoundPort(embeddedKafkaConfig) { implicit kcfg =>
        implicit val schemaRegPort = kcfg.schemaRegistryPort
        implicit val wsCfg =
          applicationTestConfig(kcfg.kafkaPort, Option(schemaRegPort))

        implicit val wsConsumerProbe = WSProbe()
        val producerProbe            = WSProbe()
        val routes                   = Route.seal(TestRoutes.routes)
        val topic                    = "test-topic-7"
        val messages                 = producerKeyValueAvro(10)

        produceAvro(
          topic = topic,
          routes = routes,
          keyType = Some(AvroType),
          messages = messages
        )(producerProbe, kcfg)

        val outPath = "/socket/out?" +
          "clientId=test-7" +
          "&groupId=test-group-7" +
          s"&topic=$topic" +
          s"&socketPayload=${AvroPayload.name}" +
          "&keyType=avro" +
          "&valType=avro" +
          "&autoCommit=false"

        implicit val keySerdes = Serdes.keySerdes.deserializer()
        implicit val valSerdes = Serdes.valueSerdes.deserializer()

        val (rk, rv) = consumeFirstKeyedMessageFrom[TestKey, Album](topic)
        rk.username mustBe "foo-1"
        rv.artist mustBe "artist-1"
        rv.title mustBe "title-1"
        rv.tracks must have size 3
        forAll(rv.tracks) { t =>
          t.name must startWith("track-")
          t.duration mustBe (120 seconds).toMillis
        }

        WS(outPath, wsConsumerProbe.flow) ~> routes ~> check {
          isWebSocketUpgrade mustBe true

          forAll(1 to 10) { i =>
            val expectedTracks = (1 to 3).map { i =>
              TestTypes.Track(s"track-$i", (120 seconds).toMillis)
            }
            wsConsumerProbe.expectWsConsumerKeyValueResultAvro(
              expectedTopic = topic,
              expectedKey = Option(TestTypes.TestKey(s"foo-$i", 1234567L)),
              expectedValue =
                TestTypes.Album(s"artist-$i", s"title-$i", expectedTracks)
            )
          }

        }
      }
  }

}
