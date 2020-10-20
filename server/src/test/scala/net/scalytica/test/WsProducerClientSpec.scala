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
import net.scalytica.kafka.wsproxy.models.TopicName
import org.scalatest.Inspectors.forAll
import org.scalatest.Suite

trait WsProducerClientSpec extends WsClientSpec { self: Suite =>

  def baseProducerUri(
      topicName: TopicName,
      payloadType: SocketPayload = JsonPayload,
      keyType: FormatType = StringType,
      valType: FormatType = StringType
  ): String = {
    val baseUri =
      "/socket/in?" +
        s"topic=${topicName.value}" +
        s"&socketPayload=${payloadType.name}" +
        s"&valType=${valType.name}"
    if (keyType != NoType) baseUri + s"&keyType=${keyType.name}" else baseUri
  }

  def produceAndCheckJson(
      topic: TopicName,
      keyType: FormatType,
      valType: FormatType,
      routes: Route,
      messages: Seq[String],
      validateMessageId: Boolean = false,
      kafkaCreds: Option[BasicHttpCredentials] = None,
      creds: Option[HttpCredentials] = None
  )(implicit wsClient: WSProbe): Unit = {
    val uri = baseProducerUri(topic, keyType = keyType, valType = valType)

    checkWebSocket(
      uri = uri,
      routes = routes,
      kafkaCreds = kafkaCreds,
      creds = creds
    ) {
      isWebSocketUpgrade mustBe true

      forAll(messages) { msg =>
        wsClient.sendMessage(msg)
        wsClient.expectWsProducerResultJson(topic, validateMessageId)
      }
      wsClient.sendCompletion()
      wsClient.expectCompletion()
    }
  }

  def produceAndCheckAvro(
      topic: TopicName,
      routes: Route,
      keyType: Option[FormatType],
      valType: FormatType,
      messages: Seq[AvroProducerRecord],
      validateMessageId: Boolean = false,
      kafkaCreds: Option[BasicHttpCredentials] = None,
      creds: Option[HttpCredentials] = None
  )(
      implicit producerProbe: WSProbe
  ): Unit = {
    val baseUri = baseProducerUri(
      topicName = topic,
      payloadType = AvroPayload,
      keyType = keyType.getOrElse(NoType),
      valType = valType
    )

    checkWebSocket(
      uri = baseUri,
      routes = routes,
      kafkaCreds = kafkaCreds,
      creds = creds
    ) {
      isWebSocketUpgrade mustBe true

      forAll(messages) { msg =>
        val bytes = avroProducerRecordSerde.serialize(msg)
        producerProbe.sendMessage(ByteString(bytes))
        producerProbe.expectWsProducerResultAvro(topic, validateMessageId)
      }
      producerProbe.sendCompletion()
      producerProbe.expectCompletion()
    }
  }

}
