package net.scalytica.test

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.{BasicHttpCredentials, HttpCredentials}
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.WSProbe
import akka.util.ByteString
import net.scalytica.kafka.wsproxy.avro.SchemaTypes.AvroProducerRecord
import net.scalytica.kafka.wsproxy.models.Formats._
import net.scalytica.kafka.wsproxy.models.{
  TopicName,
  WsProducerId,
  WsProducerInstanceId
}
import net.scalytica.kafka.wsproxy.web.SocketProtocol.{
  AvroPayload,
  JsonPayload,
  SocketPayload
}
import org.scalatest.Inspectors.forAll
import org.scalatest.{Assertion, Suite}

trait WsProducerClientSpec extends WsClientSpec { self: Suite =>

  val producerId = (prefix: String, topicNum: Int) =>
    WsProducerId(s"$prefix-producer-client-$topicNum")

  val instanceId = (id: String) => WsProducerInstanceId(id)

  protected def testTopicPrefix: String

  protected var topicCounter: Int = 0

  protected def nextTopic: String = {
    topicCounter = topicCounter + 1
    s"$testTopicPrefix-$topicCounter"
  }

  def buildProducerUri(
      producerId: Option[WsProducerId],
      instanceId: Option[WsProducerInstanceId],
      topicName: Option[TopicName],
      payloadType: Option[SocketPayload] = None,
      keyType: Option[FormatType] = None,
      valType: Option[FormatType] = None
  ): String = {
    val cidArg     = producerId.map(cid => s"clientId=${cid.value}")
    val insArg     = instanceId.map(iid => s"instanceId=${iid.value}")
    val topicArg   = topicName.map(tn => s"topic=${tn.value}")
    val payloadArg = payloadType.map(pt => s"socketPayload=${pt.name}")
    val keyArg     = keyType.map(kt => s"keyType=${kt.name}")
    val valArg     = valType.map(vt => s"valType=${vt.name}")

    val args = List(cidArg, insArg, topicArg, payloadArg, keyArg, valArg)
      .filterNot(_.isEmpty)
      .collect { case Some(arg) => arg }
      .mkString("", "&", "")

    s"/socket/in?$args"
  }

  private[this] def isProducerUrlValid(uri: String): Boolean = {
    uri.contains("clientId") && uri.contains("topic")
  }

  def baseProducerUri(
      producerId: WsProducerId,
      instanceId: Option[WsProducerInstanceId],
      topicName: TopicName,
      payloadType: SocketPayload = JsonPayload,
      keyType: FormatType = StringType,
      valType: FormatType = StringType
  ): String = {
    val baseUri =
      "/socket/in?" +
        s"clientId=${producerId.value}" +
        instanceId.map(id => s"&instanceId=${id.value}").getOrElse("") +
        s"&topic=${topicName.value}" +
        s"&socketPayload=${payloadType.name}" +
        s"&valType=${valType.name}"
    if (keyType != NoType) baseUri + s"&keyType=${keyType.name}" else baseUri
  }

  def assertProducerWS[T](
      routes: Route,
      uri: String,
      kafkaCreds: Option[BasicHttpCredentials] = None,
      creds: Option[HttpCredentials] = None
  )(body: => T)(implicit wsClient: WSProbe): T = {
    inspectWebSocket(
      uri = uri,
      routes = routes,
      kafkaCreds = kafkaCreds,
      creds = creds
    ) {
      body
    }
  }

  // scalastyle:off
  def produceAndAssertJson(
      producerId: WsProducerId,
      instanceId: Option[WsProducerInstanceId],
      topic: TopicName,
      keyType: FormatType,
      valType: FormatType,
      routes: Route,
      messages: Seq[String],
      validateMessageId: Boolean = false,
      kafkaCreds: Option[BasicHttpCredentials] = None,
      creds: Option[HttpCredentials] = None,
      producerUri: Option[String] = None
  )(implicit wsClient: WSProbe): Assertion = {
    val uri = producerUri.getOrElse {
      baseProducerUri(
        producerId = producerId,
        instanceId = instanceId,
        topicName = topic,
        keyType = keyType,
        valType = valType
      )
    }

    inspectWebSocket(
      uri = uri,
      routes = routes,
      kafkaCreds = kafkaCreds,
      creds = creds
    ) {
      if (isProducerUrlValid(uri)) {
        isWebSocketUpgrade mustBe true

        if (messages.nonEmpty) {
          forAll(messages) { msg =>
            wsClient.sendMessage(msg)
            wsClient.expectWsProducerResultJson(topic, validateMessageId)
          }
          wsClient.sendCompletion()
          wsClient.expectCompletion()
        }

        wsClient.succeed
      } else {
        isWebSocketUpgrade mustBe false
        status mustBe StatusCodes.BadRequest
      }
    }
  }

  def produceAndAssertAvro(
      producerId: WsProducerId,
      instanceId: Option[WsProducerInstanceId],
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
      implicit wsClient: WSProbe
  ): Assertion = {
    val uri = producerUri.getOrElse {
      baseProducerUri(
        producerId = producerId,
        instanceId = instanceId,
        topicName = topic,
        payloadType = AvroPayload,
        keyType = keyType.getOrElse(NoType),
        valType = valType
      )
    }

    inspectWebSocket(
      uri = uri,
      routes = routes,
      kafkaCreds = kafkaCreds,
      creds = creds
    ) {
      if (isProducerUrlValid(uri)) {
        isWebSocketUpgrade mustBe true

        forAll(messages) { msg =>
          val bytes = avroProducerRecordSerde.serialize(msg)
          wsClient.sendMessage(ByteString(bytes))
          wsClient.expectWsProducerResultAvro(topic, validateMessageId)
        }
        wsClient.sendCompletion()
        wsClient.expectCompletion()
        wsClient.succeed
      } else {
        isWebSocketUpgrade mustBe false
        status mustBe StatusCodes.BadRequest
      }
    }
  }
  // scalastyle:on
}
