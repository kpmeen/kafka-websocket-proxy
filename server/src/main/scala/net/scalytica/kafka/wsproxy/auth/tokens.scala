package net.scalytica.kafka.wsproxy.auth

import io.circe.generic.extras.auto._
import io.circe.syntax._
import org.apache.pekko.http.scaladsl.model.ContentTypes
import org.apache.pekko.http.scaladsl.model.HttpEntity
import org.apache.pekko.http.scaladsl.model.HttpMethods
import org.apache.pekko.http.scaladsl.model.HttpRequest
import org.apache.pekko.http.scaladsl.model.headers.OAuth2BearerToken
import org.apache.pekko.util.ByteString

case class TokenRequest private (
    clientId: String,
    clientSecret: String,
    audience: String,
    grantType: String
) {

  def jsonString: String = this.asJson.noSpaces

  /**
   * Creates a pekko-http [[HttpRequest]] instance from this [[TokenRequest]]
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
