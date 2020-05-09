package net.scalytica.test

import akka.http.scaladsl.model.headers.BasicHttpCredentials
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.WSProbe
import akka.util.ByteString
import net.manub.embeddedkafka.schemaregistry.EmbeddedKafkaConfig
import net.scalytica.kafka.wsproxy.SocketProtocol.{
  AvroPayload,
  JsonPayload,
  SocketPayload
}
import net.scalytica.kafka.wsproxy.avro.SchemaTypes.AvroProducerRecord
import net.scalytica.kafka.wsproxy.models.Formats._
import org.scalatest.Inspectors.forAll
import org.scalatest.Suite

trait WsProducerClientSpec extends WsClientSpec { self: Suite =>

  def baseProducerUri(
      topic: String,
      payloadType: SocketPayload = JsonPayload,
      keyType: FormatType = StringType,
      valType: FormatType = StringType
  ): String = {
    val baseUri =
      "/socket/in?" +
        s"topic=$topic" +
        s"&socketPayload=${payloadType.name}" +
        s"&valType=${valType.name}"
    if (keyType != NoType) baseUri + s"&keyType=${keyType.name}" else baseUri
  }

  def produceAndCheckJson(
      topic: String,
      keyType: FormatType,
      valType: FormatType,
      routes: Route,
      messages: Seq[String],
      basicCreds: Option[BasicHttpCredentials] = None
  )(implicit wsClient: WSProbe): Unit = {
    val uri = baseProducerUri(topic, keyType = keyType, valType = valType)
    val kt  = if (keyType == NoType) None else Option(keyType)

    checkWebSocket(uri, routes, kt, basicCreds) {
      isWebSocketUpgrade mustBe true

      forAll(messages) { msg =>
        wsClient.sendMessage(msg)
        wsClient.expectWsProducerResultJson(topic)
      }
      wsClient.sendCompletion()
      wsClient.expectCompletion()
    }
  }

  def produceAndCheckAvro(
      topic: String,
      routes: Route,
      keyType: Option[FormatType],
      messages: Seq[AvroProducerRecord],
      basicCreds: Option[BasicHttpCredentials] = None
  )(
      implicit
      wsClient: WSProbe,
      kafkaCfg: EmbeddedKafkaConfig
  ): Unit = {
    implicit val schemaRegPort = kafkaCfg.schemaRegistryPort

    val serializer = avroProducerRecordSerde(kafkaCfg.schemaRegistryPort)
    val baseUri = baseProducerUri(
      topic = topic,
      payloadType = AvroPayload,
      keyType = keyType.getOrElse(NoType),
      valType = AvroType
    )

    checkWebSocket(
      baseUri,
      routes,
      keyType,
      basicCreds
    ) {
      isWebSocketUpgrade mustBe true

      forAll(messages) { msg =>
        val bytes = serializer.serialize(msg)
        wsClient.sendMessage(ByteString(bytes))
        wsClient.expectWsProducerResultAvro(topic)
      }
      wsClient.sendCompletion()
      wsClient.expectCompletion()
    }
  }

}
