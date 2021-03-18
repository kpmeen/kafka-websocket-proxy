package net.scalytica.kafka.wsproxy.models

import akka.http.scaladsl.model.headers.OAuth2BearerToken
import io.circe.parser._
import net.scalytica.kafka.wsproxy.config.Configuration.AppCfg
import pdi.jwt.JwtClaim

sealed trait AuthenticationResult {

  def aclCredentials: Option[AclCredentials]      = None
  def maybeBearerToken: Option[OAuth2BearerToken] = None

}

case class JwtAuthResult(
    bearerToken: OAuth2BearerToken,
    claim: JwtClaim
)(implicit cfg: AppCfg)
    extends AuthenticationResult {

  private[this] val maybeCreds = cfg.server.customJwtKafkaCredsKeys

  override def maybeBearerToken = Option(bearerToken)

  override def aclCredentials: Option[AclCredentials] = {
    maybeCreds.flatMap { case (uk, pk) =>
      parse(claim.toJson).toOption.flatMap { js =>
        for {
          user <- js.hcursor.downField(uk).as[String].toOption
          pass <- js.hcursor.downField(pk).as[String].toOption
        } yield AclCredentials(user, pass)
      }
    }
  }

}

case class BasicAuthResult(id: String) extends AuthenticationResult

case object AuthDisabled extends AuthenticationResult
