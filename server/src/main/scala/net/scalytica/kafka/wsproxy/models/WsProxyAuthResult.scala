package net.scalytica.kafka.wsproxy.models

import org.apache.pekko.http.scaladsl.model.headers.OAuth2BearerToken
import io.circe.Json
import io.circe.parser._
import net.scalytica.kafka.wsproxy.config.Configuration.AppCfg
import net.scalytica.kafka.wsproxy.logging.WithProxyLogger
import pdi.jwt.JwtClaim

sealed trait WsProxyAuthResult {

  def aclCredentials: Option[AclCredentials]      = None
  def maybeBearerToken: Option[OAuth2BearerToken] = None

}

case class JwtAuthResult(
    bearerToken: OAuth2BearerToken,
    claim: JwtClaim
)(implicit cfg: AppCfg)
    extends WsProxyAuthResult
    with WithProxyLogger {

  private[this] val maybeCreds = cfg.server.customJwtKafkaCredsKeys

  private[this] def mapClaim[T](
      userKey: String,
      passwordKey: String
  )(
      valid: (String, String) => T
  )(
      invalid: => T
  ): T = {
    parse(claim.toJson).toOption
      .flatMap { js =>
        for {
          user <- parseKey(userKey, js)
          pass <- parseKey(passwordKey, js)
        } yield {
          log.trace("Correctly parsed custom JWT attributes")
          valid(user, pass)
        }
      }
      .getOrElse {
        // This should _really_ not happen. Since the claim must have been
        // correctly parsed before this function is executed.
        log.warn("JWT Claim has an invalid format.")
        invalid
      }
  }

  override def maybeBearerToken: Option[OAuth2BearerToken] = Option(bearerToken)

  def isValid: Boolean = {
    maybeCreds
      .map { case (uk, pk) =>
        mapClaim(uk, pk)((_, _) => true)(false)
      }
      .getOrElse {
        if (cfg.server.isKafkaTokenAuthOnlyEnabled) false
        else true
      }
  }

  override def aclCredentials: Option[AclCredentials] = {
    maybeCreds.flatMap { case (uk, pk) =>
      mapClaim[Option[AclCredentials]](uk, pk) { (user, pass) =>
        Option(AclCredentials(user, pass))
      }(None)
    }
  }

  private[this] def parseKey(
      key: String,
      json: Json
  ): Option[String] = {
    json.hcursor.downField(key).as[String] match {
      case Right(value) =>
        log.trace(s"Found JWT Kafka credentials key $key with value $value")
        Some(value)
      case Left(err) =>
        val msg = "Could not find expected Kafka credentials key " +
          s"'$key' in OAuth Bearer token."
        log.warn(msg, err)
        None
    }
  }

}

case class BasicAuthResult(id: String) extends WsProxyAuthResult

case object AuthDisabled extends WsProxyAuthResult
