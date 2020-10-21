package net.scalytica.test

import akka.http.scaladsl.model.headers.{BasicHttpCredentials, HttpCredentials}
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.WSProbe
import akka.util.ByteString
import net.scalytica.kafka.wsproxy.avro.SchemaTypes.AvroProducerRecord
import net.scalytica.kafka.wsproxy.models.Formats._
import net.scalytica.kafka.wsproxy.models.{TopicName, WsClientId}
import net.scalytica.kafka.wsproxy.web.SocketProtocol.{
  AvroPayload,
  JsonPayload,
  SocketPayload
}
import org.scalatest.Inspectors.forAll
import org.scalatest.Suite

trait WsProducerClientSpec extends WsClientSpec { self: Suite =>

  val producerClientId = (prefix: String, topicNum: Int) =>
    WsClientId(
      s"$prefix-producer-client-$topicNum"
    )

  def buildProducerUri(
      clientId: Option[WsClientId],
      topicName: Option[TopicName],
      payloadType: Option[SocketPayload] = None,
      keyType: Option[FormatType] = None,
      valType: Option[FormatType] = None
  ): String = {
    val cidArg     = clientId.map(cid => s"clientId=${cid.value}")
    val topicArg   = topicName.map(tn => s"topic=${tn.value}")
    val payloadArg = payloadType.map(pt => s"socketPayload=${pt.name}")
    val keyArg     = keyType.map(kt => s"keyType=${kt.name}")
    val valArg     = valType.map(vt => s"valType=${vt.name}")

    val args = List(cidArg, topicArg, payloadArg, keyArg, valArg)
      .filterNot(_.isEmpty)
      .mkString("", "&", "")

    s"/socket/in?$args"
  }

  def baseProducerUri(
      clientId: WsClientId,
      topicName: TopicName,
      payloadType: SocketPayload = JsonPayload,
      keyType: FormatType = StringType,
      valType: FormatType = StringType
  ): String = {
    val baseUri =
      "/socket/in?" +
        s"clientId=${clientId.value}" +
        s"&topic=${topicName.value}" +
        s"&socketPayload=${payloadType.name}" +
        s"&valType=${valType.name}"
    if (keyType != NoType) baseUri + s"&keyType=${keyType.name}" else baseUri
  }

  def produceAndCheckJson(
      clientId: WsClientId,
      topic: TopicName,
      keyType: FormatType,
      valType: FormatType,
      routes: Route,
      messages: Seq[String],
      validateMessageId: Boolean = false,
      kafkaCreds: Option[BasicHttpCredentials] = None,
      creds: Option[HttpCredentials] = None
  )(implicit wsClient: WSProbe): Unit = {
    val uri = baseProducerUri(
      clientId = clientId,
      topicName = topic,
      keyType = keyType,
      valType = valType
    )

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

  // scalastyle:off
  def produceAndCheckAvro(
      clientId: WsClientId,
      topic: TopicName,
      routes: Route,
      keyType: Option[FormatType],
      valType: FormatType,
      messages: Seq[AvroProducerRecord],
      validateMessageId: Boolean = false,
      kafkaCreds: Option[BasicHttpCredentials] = None,
      creds: Option[HttpCredentials] = None,
      producerUri: Option[String] = None
  )(
      implicit producerProbe: WSProbe
  ): Unit = {
    val baseUri = producerUri.getOrElse {
      baseProducerUri(
        clientId = clientId,
        topicName = topic,
        payloadType = AvroPayload,
        keyType = keyType.getOrElse(NoType),
        valType = valType
      )
    }

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
  // scalastyle:on
}
