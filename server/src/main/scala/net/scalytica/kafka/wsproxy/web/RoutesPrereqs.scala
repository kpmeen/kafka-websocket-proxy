package net.scalytica.kafka.wsproxy.web

import net.scalytica.kafka.wsproxy.models.WsServerId

import io.circe.Json
import org.apache.pekko.http.scaladsl.model.ContentTypes
import org.apache.pekko.http.scaladsl.model.HttpEntity
import org.apache.pekko.http.scaladsl.model.HttpResponse
import org.apache.pekko.http.scaladsl.model.StatusCode

trait RoutesPrereqs {
  protected val serverId: WsServerId

  implicit protected def jsonToString(json: Json): String = json.spaces2

  protected def jsonMessageFromString(msg: String): Json =
    Json.obj("message" -> Json.fromString(msg))

  protected def jsonResponseMsg(
      statusCode: StatusCode,
      message: String
  ): HttpResponse = {
    HttpResponse(
      status = statusCode,
      entity = HttpEntity(
        contentType = ContentTypes.`application/json`,
        string = jsonMessageFromString(message)
      )
    )
  }
}
