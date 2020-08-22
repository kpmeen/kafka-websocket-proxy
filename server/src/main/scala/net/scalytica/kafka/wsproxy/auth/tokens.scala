package net.scalytica.kafka.wsproxy.auth

import io.circe.syntax._
import io.circe.generic.extras.auto._
import akka.http.scaladsl.model.{
  ContentTypes,
  HttpEntity,
  HttpMethods,
  HttpRequest
}
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.util.ByteString

case class TokenRequest private (
    clientId: String,
    clientSecret: String,
    audience: String,
    grantType: String
) {

  def jsonString: String = this.asJson.noSpaces

  /**
   * Creates an akka-http [[HttpRequest]] instance from this [[TokenRequest]]
   *
   * @param url
   *   The URL that the request will be executed against
   * @return
   *   a instance of [[HttpRequest]]
   */
  def request(url: String): HttpRequest = {
    val entity = HttpEntity.Strict(
      contentType = ContentTypes.`application/json`,
      data = ByteString(jsonString)
    )
    HttpRequest(
      method = HttpMethods.POST,
      uri = url,
      entity = entity
    )
  }
}

case class AccessToken(
    tokenType: String,
    accessToken: String,
    expiresIn: Long,
    refreshToken: Option[String]
) {

  /**
   * @return
   *   An instance of a [[OAuth2BearerToken]] based on the {{accessToken}}
   */
  def bearerToken: OAuth2BearerToken = OAuth2BearerToken(accessToken)

}
