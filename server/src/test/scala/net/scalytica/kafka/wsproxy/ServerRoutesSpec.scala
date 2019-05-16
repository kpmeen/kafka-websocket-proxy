package net.scalytica.kafka.wsproxy

import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server._
import akka.http.scaladsl.testkit.{RouteTestTimeout, WSProbe}
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
    with WSProxyKafkaSpec
    with WsProducerClients
    with TestDataGenerators
    with EmbeddedKafka {

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(2, Minutes))

  implicit val timeout = RouteTestTimeout(20 seconds)

  def initTopic(topicName: String, partitions: Int = 1)(
      implicit kcfg: EmbeddedKafkaConfig
  ): Unit = createCustomTopic(
    topic = topicName,
    partitions = partitions
  )

  case object TestRoutes extends ServerRoutes

  import TestRoutes.{serverErrorHandler, serverRejectionHandler}

  "The server routes" should {
    "return a 404 NotFound when requesting an invalid resource" in {
      implicit val cfg = defaultTestAppCfgWithServerId("n1")

      val expected =
        "{\"message\":\"This is not the resource you are looking for.\"}"

      val (_, testRoutes) = TestRoutes.wsProxyRoutes

      val routes = Route.seal(testRoutes)

      Get() ~> routes ~> check {
        status mustBe NotFound
        responseAs[String] mustBe expected
      }
    }

    "return the Avro schema for producer records" in {
      implicit val cfg    = defaultTestAppCfgWithServerId("n2")
      val (_, testRoutes) = TestRoutes.wsProxyRoutes

      Get("/schemas/avro/producer/record") ~> testRoutes ~> check {
        status mustBe OK
        responseAs[String] mustBe AvroProducerRecord.schemaFor.schema
          .toString(true)
      }
    }

    "return the Avro schema for producer results" in {
      implicit val cfg    = defaultTestAppCfgWithServerId("n3")
      val (_, testRoutes) = TestRoutes.wsProxyRoutes

      Get("/schemas/avro/producer/result") ~> testRoutes ~> check {
        status mustBe OK
        responseAs[String] mustBe AvroProducerResult.schemaFor.schema
          .toString(true)
      }
    }

    "return the Avro schema for consumer record" in {
      implicit val cfg    = defaultTestAppCfgWithServerId("n4")
      val (_, testRoutes) = TestRoutes.wsProxyRoutes

      Get("/schemas/avro/consumer/record") ~> testRoutes ~> check {
        status mustBe OK
        responseAs[String] mustBe AvroConsumerRecord.schemaFor.schema
          .toString(true)
      }
    }

    "return the Avro schema for consumer commit" in {
      implicit val cfg    = defaultTestAppCfgWithServerId("n5")
      val (_, testRoutes) = TestRoutes.wsProxyRoutes

      Get("/schemas/avro/consumer/commit") ~> testRoutes ~> check {
        status mustBe OK
        responseAs[String] mustBe AvroCommit.schemaFor.schema.toString(true)
      }
    }

    "set up a WebSocket connection for producing JSON key value messages" in
      withRunningKafkaOnFoundPort(embeddedKafkaConfig) { implicit kcfg =>
        implicit val wsCfg =
          appTestConfig(kafkaPort = kcfg.kafkaPort, serverId = "n6")

        val topicName = "test-topic-1"
        initTopic(topicName)

        implicit val wsClient       = WSProbe()
        val (sdcStream, testRoutes) = TestRoutes.wsProxyRoutes
        val ctrl                    = sdcStream.run()
        val msgs                    = producerKeyValueJson(1)

        produceJson(topicName, StringType, StringType, testRoutes, msgs)

        ctrl.shutdown()
      }

    "set up a WebSocket connection for producing JSON value messages" in
      withRunningKafkaOnFoundPort(embeddedKafkaConfig) { implicit kcfg =>
        implicit val wsCfg =
          appTestConfig(kafkaPort = kcfg.kafkaPort, serverId = "n7")

        val topicName = "test-topic-2"
        initTopic(topicName)

        implicit val wsClient       = WSProbe()
        val (sdcStream, testRoutes) = TestRoutes.wsProxyRoutes
        val ctrl                    = sdcStream.run()
        val msgs                    = producerValueJson(1)

        produceJson(topicName, NoType, StringType, testRoutes, msgs)

        ctrl.shutdown()
      }

    "set up a WebSocket connection for consuming JSON key value messages" in
      withRunningKafkaOnFoundPort(embeddedKafkaConfig) { implicit kcfg =>
        implicit val wsCfg =
          appTestConfig(kafkaPort = kcfg.kafkaPort, serverId = "n8")

        val topicName = "test-topic-3"
        initTopic(topicName)

        implicit val wsConsumerProbe = WSProbe()
        val producerProbe            = WSProbe()
        val (sdcStream, testRoutes)  = TestRoutes.wsProxyRoutes
        val ctrl                     = sdcStream.run()

        produceJson(
          topic = topicName,
          keyType = StringType,
          valType = StringType,
          routes = testRoutes,
          messages = producerKeyValueJson(10)
        )(producerProbe)

        val outPath = "/socket/out?" +
          "clientId=test-3" +
          "&groupId=test-group-3" +
          s"&topic=$topicName" +
          "&keyType=string" +
          "&valType=string" +
          "&autoCommit=false"

        import net.manub.embeddedkafka.Codecs.stringDeserializer

        val (rk, rv) = consumeFirstKeyedMessageFrom[String, String](topicName)
        rk mustBe "foo-1"
        rv mustBe "bar-1"

        WS(outPath, wsConsumerProbe.flow) ~> testRoutes ~> check {
          isWebSocketUpgrade mustBe true

          forAll(1 to 10) { i =>
            wsConsumerProbe.expectWsConsumerKeyValueResultJson[String, String](
              expectedTopic = topicName,
              expectedKey = s"foo-$i",
              expectedValue = s"bar-$i"
            )
          }

        }

        ctrl.shutdown()
      }

    "set up a WebSocket connection for consuming JSON value messages" in
      withRunningKafkaOnFoundPort(embeddedKafkaConfig) { implicit kcfg =>
        implicit val wsCfg =
          appTestConfig(kafkaPort = kcfg.kafkaPort, serverId = "n9")

        val topicName = "test-topic-4"
        initTopic(topicName)

        implicit val wsConsumerProbe = WSProbe()
        val producerProbe            = WSProbe()
        val (sdcStream, testRoutes)  = TestRoutes.wsProxyRoutes
        val ctrl                     = sdcStream.run()

        produceJson(
          topic = topicName,
          keyType = NoType,
          valType = StringType,
          routes = testRoutes,
          messages = producerValueJson(10)
        )(producerProbe)

        val outPath = "/socket/out?" +
          "clientId=test-4" +
          "&groupId=test-group-4" +
          s"&topic=$topicName" +
          "&valType=string" +
          "&autoCommit=false"

        import net.manub.embeddedkafka.Codecs.stringDeserializer

        consumeFirstMessageFrom[String](topicName) mustBe "bar-1"

        WS(outPath, wsConsumerProbe.flow) ~> testRoutes ~> check {
          isWebSocketUpgrade mustBe true

          forAll(1 to 10) { i =>
            wsConsumerProbe.expectWsConsumerValueResultJson[String](
              expectedTopic = topicName,
              expectedValue = s"bar-$i"
            )
          }
        }

        ctrl.shutdown()
      }

    "set up a WebSocket connection for producing Avro key value messages" in
      withRunningKafkaOnFoundPort(embeddedKafkaConfig) { implicit kcfg =>
        implicit val wsCfg =
          appTestConfig(kcfg.kafkaPort, Option(kcfg.schemaRegistryPort), "n10")

        val topicName = "test-topic-5"
        initTopic(topicName)

        implicit val wsClient       = WSProbe()
        val (sdcStream, testRoutes) = TestRoutes.wsProxyRoutes
        val ctrl                    = sdcStream.run()
        val messages                = producerKeyValueAvro(1)

        produceAvro(topicName, testRoutes, Some(AvroType), messages)

        ctrl.shutdown()
      }

    "set up a WebSocket connection for producing Avro value messages" in
      withRunningKafkaOnFoundPort(embeddedKafkaConfig) { implicit kcfg =>
        implicit val wsCfg =
          appTestConfig(kcfg.kafkaPort, Option(kcfg.schemaRegistryPort), "n11")

        val topicName = "test-topic-6"
        initTopic(topicName)

        implicit val wsClient       = WSProbe()
        val (sdcStream, testRoutes) = TestRoutes.wsProxyRoutes
        val ctrl                    = sdcStream.run()
        val messages                = producerValueAvro(1)

        produceAvro(topicName, testRoutes, None, messages)

        ctrl.shutdown()
      }

    "set up a WebSocket connection for consuming Avro key value messages" in
      withRunningKafkaOnFoundPort(embeddedKafkaConfig) { implicit kcfg =>
        implicit val schemaRegPort = kcfg.schemaRegistryPort
        implicit val wsCfg =
          appTestConfig(kcfg.kafkaPort, Option(schemaRegPort), "n12")

        val topicName = "test-topic-7"
        initTopic(topicName)

        implicit val wsConsumerProbe = WSProbe()
        val producerProbe            = WSProbe()
        val (sdcStream, testRoutes)  = TestRoutes.wsProxyRoutes
        val ctrl                     = sdcStream.run()
        val messages                 = producerKeyValueAvro(10)

        produceAvro(
          topic = topicName,
          routes = testRoutes,
          keyType = Some(AvroType),
          messages = messages
        )(producerProbe, kcfg)

        val outPath = "/socket/out?" +
          "clientId=test-7" +
          "&groupId=test-group-7" +
          s"&topic=$topicName" +
          s"&socketPayload=${AvroPayload.name}" +
          "&keyType=avro" +
          "&valType=avro" +
          "&autoCommit=false"

        implicit val keySerdes = Serdes.keySerdes.deserializer()
        implicit val valSerdes = Serdes.valueSerdes.deserializer()

        val (rk, rv) = consumeFirstKeyedMessageFrom[TestKey, Album](topicName)
        rk.username mustBe "foo-1"
        rv.artist mustBe "artist-1"
        rv.title mustBe "title-1"
        rv.tracks must have size 3
        forAll(rv.tracks) { t =>
          t.name must startWith("track-")
          t.duration mustBe (120 seconds).toMillis
        }

        WS(outPath, wsConsumerProbe.flow) ~> testRoutes ~> check {
          isWebSocketUpgrade mustBe true

          forAll(1 to 10) { i =>
            val expectedTracks = (1 to 3).map { i =>
              TestTypes.Track(s"track-$i", (120 seconds).toMillis)
            }
            wsConsumerProbe.expectWsConsumerKeyValueResultAvro(
              expectedTopic = topicName,
              expectedKey = Option(TestTypes.TestKey(s"foo-$i", 1234567L)),
              expectedValue =
                TestTypes.Album(s"artist-$i", s"title-$i", expectedTracks)
            )
          }

        }

        ctrl.shutdown()
      }
  }

}
