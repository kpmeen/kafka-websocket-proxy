package net.scalytica.kafka.wsproxy.web

import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success

import net.scalytica.kafka.wsproxy.auth.OpenIdClient
import net.scalytica.kafka.wsproxy.config.Configuration.AppCfg
import net.scalytica.kafka.wsproxy.errors._
import net.scalytica.kafka.wsproxy.logging.WithProxyLogger
import net.scalytica.kafka.wsproxy.models._
import net.scalytica.kafka.wsproxy.web.Headers.XKafkaAuthHeader

import org.apache.pekko.http.scaladsl.model.headers.OAuth2BearerToken
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server._
import org.apache.pekko.http.scaladsl.server.directives.Credentials
import org.apache.pekko.stream.Materializer

/**
 * The base routing implementation. Defines authentication, error and rejection
 * handling, as well as other shared implementations.
 */
trait BaseRoutes
    extends RoutesPrereqs
    with RouteFailureHandlers
    with WithProxyLogger {

  protected def extractKafkaCreds(
      authRes: WsProxyAuthResult,
      kafkaAuthHeader: Option[XKafkaAuthHeader]
  )(implicit cfg: AppCfg): Option[AclCredentials] = {
    cfg.server.openidConnect
      .map { oidcfg =>
        if (oidcfg.isKafkaTokenAuthOnlyEnabled) {
          log.trace("Only allowing Kafka auth through JWT token.")
          authRes.aclCredentials
        } else {
          log.trace(
            "Allowing Kafka auth through JWT token or the" +
              s" ${Headers.KafkaAuthHeaderName} header."
          )
          // Always prefer the JWT token
          authRes.aclCredentials.orElse(kafkaAuthHeader.map(_.aclCredentials))
        }
      }
      .getOrElse {
        log.trace(
          "OpenID Connect is not configured. Using" +
            s" ${Headers.KafkaAuthHeaderName} header."
        )
        kafkaAuthHeader.map(_.aclCredentials)
      }
  }

  protected def basicAuthCredentials(
      creds: Credentials
  )(implicit cfg: AppCfg): Option[WsProxyAuthResult] = {
    cfg.server.basicAuth
      .flatMap { bac =>
        for {
          u <- bac.username
          p <- bac.password
        } yield (u, p)
      }
      .map { case (usr, pwd) =>
        creds match {
          case p @ Credentials.Provided(id) // constant time comparison
              if usr.equals(id) && p.verify(pwd) =>
            log.trace("Successfully authenticated bearer token.")
            Some(BasicAuthResult(id))

          case _ =>
            log.info("Could not authenticate basic auth credentials")
            None
        }
      }
      .getOrElse(Some(AuthDisabled))
  }

  protected def openIdAuth(
      creds: Credentials
  )(
      implicit appCfg: AppCfg,
      maybeOpenIdClient: Option[OpenIdClient],
      mat: Materializer
  ): Future[Option[WsProxyAuthResult]] = {
    log.trace(s"Going to validate openid token $creds")
    implicit val ec = mat.executionContext

    maybeOpenIdClient match {
      case Some(oidcClient) =>
        creds match {
          case Credentials.Provided(token) =>
            val bearerToken = OAuth2BearerToken(token)
            oidcClient.validate(bearerToken).flatMap {
              case Success(jwtClaim) =>
                log.trace("Successfully authenticated bearer token.")
                val jar = JwtAuthResult(bearerToken, jwtClaim)
                if (jar.isValid) Future.successful(Some(jar))
                else Future.successful(None)
              case Failure(err) =>
                err match {
                  case err: ProxyAuthError =>
                    log.info("Could not authenticate bearer token", err)
                    Future.successful(None)
                  case err =>
                    Future.failed(err)
                }
            }
          case _ =>
            log.info("Could not authenticate bearer token")
            Future.successful(None)
        }
      case None =>
        log.info("OpenID Connect is not enabled")
        Future.successful(None)
    }
  }

  protected def maybeAuthenticateOpenId[T](
      implicit cfg: AppCfg,
      maybeOpenIdClient: Option[OpenIdClient],
      mat: Materializer
  ): Directive1[WsProxyAuthResult] = {
    log.debug("Attempting authentication using openid-connect...")
    cfg.server.openidConnect
      .flatMap { oidcCfg =>
        val realm = oidcCfg.realm.getOrElse("")
        if (oidcCfg.enabled) Option(authenticateOAuth2Async(realm, openIdAuth))
        else None
      }
      .getOrElse {
        log.info("OpenID Connect is not enabled.")
        provide(AuthDisabled)
      }
  }

  protected def maybeAuthenticateBasic[T](
      implicit cfg: AppCfg
  ): Directive1[WsProxyAuthResult] = {
    log.debug("Attempting authentication using basic authentication...")
    cfg.server.basicAuth
      .flatMap { ba =>
        if (ba.enabled)
          ba.realm.map(r => authenticateBasic(r, basicAuthCredentials))
        else None
      }
      .getOrElse {
        log.info("Basic authentication is not enabled.")
        provide(AuthDisabled)
      }
  }

  protected def maybeAuthenticate[T](
      implicit cfg: AppCfg,
      maybeOpenIdClient: Option[OpenIdClient],
      mat: Materializer
  ): Directive1[WsProxyAuthResult] = {
    if (cfg.server.isOpenIdConnectEnabled) maybeAuthenticateOpenId[T]
    else if (cfg.server.isBasicAuthEnabled) maybeAuthenticateBasic[T]
    else provide(AuthDisabled)
  }

}
