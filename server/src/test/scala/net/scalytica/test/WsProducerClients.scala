package net.scalytica.test

import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.{ScalatestRouteTest, WSProbe}
import akka.util.ByteString
import net.manub.embeddedkafka.schemaregistry.EmbeddedKafkaConfig
import net.scalytica.kafka.wsproxy.SocketProtocol.AvroPayload
import net.scalytica.kafka.wsproxy.avro.SchemaTypes.{
  AvroConsumerRecord,
  AvroProducerRecord,
  AvroProducerResult
}
import net.scalytica.kafka.wsproxy.codecs.WsProxyAvroSerde
import net.scalytica.kafka.wsproxy.models.Formats
import net.scalytica.kafka.wsproxy.models.Formats._
import org.scalatest.Inspectors.forAll
import org.scalatest.{MustMatchers, Suite}

trait WsProducerClients extends ScalatestRouteTest with MustMatchers {
  self: Suite =>

  def produceJson(
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
        wsClient.expectWsProducerResultJson(topic)
      }
      wsClient.sendCompletion()
      wsClient.expectCompletion()
    }
  }

  def avroProducerRecordSerde(
      implicit schemaRegistryPort: Int
  ): WsProxyAvroSerde[AvroProducerRecord] =
    WsProxyAvroSerde[AvroProducerRecord](registryConfig)

  implicit def avroProducerResultSerde(
      implicit schemaRegistryPort: Int
  ): WsProxyAvroSerde[AvroProducerResult] =
    WsProxyAvroSerde[AvroProducerResult](registryConfig)

  implicit def avroConsumerRecordSerde(
      implicit schemaRegistryPort: Int
  ): WsProxyAvroSerde[AvroConsumerRecord] =
    WsProxyAvroSerde[AvroConsumerRecord](registryConfig)

  def produceAvro(
      topic: String,
      routes: Route,
      keyType: Option[FormatType],
      messages: Seq[AvroProducerRecord]
  )(
      implicit
      wsClient: WSProbe,
      kafkaCfg: EmbeddedKafkaConfig
  ): Unit = {
    implicit val schemaRegPort = kafkaCfg.schemaRegistryPort

    val serializer = avroProducerRecordSerde(kafkaCfg.schemaRegistryPort)
    val baseUri =
      s"/socket/in?" +
        s"topic=$topic&" +
        s"socketPayload=${AvroPayload.name}&" +
        s"valType=${Formats.AvroType.name}"

    val uri = keyType.fold(baseUri)(kt => baseUri + s"&keyType=${kt.name}")

    WS(uri, wsClient.flow) ~> routes ~> check {
      isWebSocketUpgrade mustBe true

      forAll(messages) { msg =>
        val bytes = serializer.serialize("", msg)
        wsClient.sendMessage(ByteString(bytes))
        wsClient.expectWsProducerResultAvro(topic)
      }
      wsClient.sendCompletion()
      wsClient.expectCompletion()
    }
  }

}
