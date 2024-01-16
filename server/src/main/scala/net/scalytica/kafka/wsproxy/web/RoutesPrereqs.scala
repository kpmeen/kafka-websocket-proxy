package net.scalytica.kafka.wsproxy.web

import org.apache.pekko.http.scaladsl.model.{
  ContentTypes,
  HttpEntity,
  HttpResponse,
  StatusCode
}
import io.circe.Json
import net.scalytica.kafka.wsproxy.models.WsServerId

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
