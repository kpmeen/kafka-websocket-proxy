package net.scalytica.test

import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.model.headers.{
  BasicHttpCredentials,
  HttpCredentials
}
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.testkit.WSProbe
import net.scalytica.kafka.wsproxy.models.Formats._
import net.scalytica.kafka.wsproxy.models.{
  TopicName,
  WsProducerId,
  WsProducerInstanceId
}
import net.scalytica.kafka.wsproxy.web.SocketProtocol.{
  JsonPayload,
  SocketPayload
}
import org.scalatest.Inspectors.forAll
import org.scalatest.{Assertion, Suite}

trait WsProducerClientSpec extends WsClientSpec { self: Suite =>

  val producerId: (String, Int) => WsProducerId =
    (prefix: String, topicNum: Int) =>
      WsProducerId(s"$prefix-producer-client-$topicNum")

  val instanceId: String => WsProducerInstanceId = (id: String) =>
    WsProducerInstanceId(id)

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
      valType: Option[FormatType] = None,
      transactional: Option[Boolean] = None
  ): String = {
    val cidArg     = producerId.map(cid => s"clientId=${cid.value}")
    val insArg     = instanceId.map(iid => s"instanceId=${iid.value}")
    val topicArg   = topicName.map(tn => s"topic=${tn.value}")
    val payloadArg = payloadType.map(pt => s"socketPayload=${pt.name}")
    val keyArg     = keyType.map(kt => s"keyType=${kt.name}")
    val valArg     = valType.map(vt => s"valType=${vt.name}")
    val transArg   = transactional.map(t => s"transactional=$t")

    val args =
      List(cidArg, insArg, topicArg, payloadArg, keyArg, valArg, transArg)
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
      topicName: Option[TopicName],
      payloadType: SocketPayload = JsonPayload,
      keyType: FormatType = StringType,
      valType: FormatType = StringType,
      exactlyOnce: Boolean = false
  ): String = {
    topicName
      .map { topic =>
        val keyTypeArg =
          if (keyType != NoType) s"&keyType=${keyType.name}" else ""

        val transactionalArg =
          if (exactlyOnce) s"&transactional=$exactlyOnce" else ""

        "/socket/in?" +
          s"clientId=${producerId.value}" +
          instanceId.map(id => s"&instanceId=${id.value}").getOrElse("") +
          s"&topic=${topic.value}" +
          s"&socketPayload=${payloadType.name}" +
          s"&valType=${valType.name}" +
          keyTypeArg +
          transactionalArg
      }
      .getOrElse(fail("Test requires a TopicName"))
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
      exactlyOnce: Boolean = false,
      validateMessageId: Boolean = false,
      kafkaCreds: Option[BasicHttpCredentials] = None,
      creds: Option[HttpCredentials] = None,
      producerUri: Option[String] = None
  )(implicit wsClient: WSProbe): Assertion = {
    val uri = producerUri.getOrElse {
      baseProducerUri(
        producerId = producerId,
        instanceId = instanceId,
        topicName = Some(topic),
        keyType = keyType,
        valType = valType,
        exactlyOnce = exactlyOnce
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
  // scalastyle:on
}
