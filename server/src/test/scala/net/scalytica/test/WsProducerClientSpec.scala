package net.scalytica.test

import akka.http.scaladsl.model.headers.{BasicHttpCredentials, HttpCredentials}
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.WSProbe
import akka.util.ByteString
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

    checkWebSocket(uri, routes, basicCreds) {
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
      valType: FormatType,
      messages: Seq[AvroProducerRecord],
      kafkaCreds: Option[BasicHttpCredentials] = None,
      creds: Option[HttpCredentials] = None
  )(
      implicit wsClient: WSProbe
  ): Unit = {
    val baseUri = baseProducerUri(
      topic = topic,
      payloadType = AvroPayload,
      keyType = keyType.getOrElse(NoType),
      valType = valType
    )

    checkWebSocket(
      baseUri,
      routes,
      kafkaCreds,
      creds
    ) {
      isWebSocketUpgrade mustBe true

      forAll(messages) { msg =>
        val bytes = avroProducerRecordSerde.serialize(msg)
        wsClient.sendMessage(ByteString(bytes))
        wsClient.expectWsProducerResultAvro(topic)
      }
      wsClient.sendCompletion()
      wsClient.expectCompletion()
    }
  }

}
