package net.scalytica.kafka.wsproxy.auth

import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.model.headers.OAuth2BearerToken
import org.apache.pekko.stream.{Materializer, StreamTcpException}
import io.circe._
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.auto._
import io.circe.parser._
import net.scalytica.kafka.wsproxy.OptionExtensions
import net.scalytica.kafka.wsproxy.config.Configuration.{
  AppCfg,
  OpenIdConnectCfg
}
import net.scalytica.kafka.wsproxy.errors.{
  AuthenticationError,
  InvalidTokenError,
  OpenIdConnectError,
  ProxyAuthError
}
import net.scalytica.kafka.wsproxy.logging.WithProxyLogger
import org.apache.pekko.actor.ActorSystem
import pdi.jwt.exceptions.JwtException
import pdi.jwt.{JwtBase64, JwtCirce, JwtClaim}

import java.security.PublicKey
import java.time.Clock
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.{Failure, Success, Try}

class OpenIdClient private (
    oidcCfg: OpenIdConnectCfg,
    enforceHttps: Boolean
)(
    implicit mat: Materializer
) extends WithProxyLogger {

  require(
    oidcCfg.wellKnownUrl.isDefined && oidcCfg.audience.isDefined,
    s"Cannot initialise $getClass without a valid OpenIdConnectCfg"
  )

  implicit private[this] val as: ActorSystem              = mat.system
  implicit private[this] val ec: ExecutionContextExecutor = mat.executionContext

  implicit val circeConfig: Configuration =
    Configuration.default.withSnakeCaseMemberNames

  implicit private[this] val clock: Clock = Clock.systemUTC()

  // A regex that defines the JWT pattern and allows us to
  // extract the header, claims and signature
  private[this] val jwtRegex      = """(.+?)\.(.+?)\.(.+?)""".r
  private[this] val audience      = oidcCfg.audience.getUnsafe
  private[this] val oidcWellKnown = oidcCfg.wellKnownUrl.getUnsafe

  private[this] val splitToken = (jwt: String) =>
    jwt match {
      case jwtRegex(header, body, sig) =>
        Success((header, body, sig))

      case _ =>
        Failure(AuthenticationError("Token does not match the correct pattern"))
    }

  // Decodes the base64-encoded header and body
  private[this] val decodeElements = (data: Try[(String, String, String)]) =>
    data
      .map { case (header, body, sig) =>
        (JwtBase64.decodeString(header), JwtBase64.decodeString(body), sig)
      }
      .recover { case t: Throwable =>
        throw AuthenticationError("Token is not valid a valid JWT", Option(t))
      }

  private[this] def getJwk(
      token: String
  )(implicit oidcConfig: OpenIdConnectConfig): Future[Try[Jwk]] = {
    Future.fromTry((splitToken andThen decodeElements)(token)).flatMap {
      case (header, _, _) =>
        val jwtHeader = Try(JwtCirce.parseHeader(header)).toOption
        val jwkProvider =
          UrlJwkProvider(oidcConfig.jwksUri, enforceHttps)

        // Use jwkProvider to load the JWK data and return the JWK
        jwtHeader
          .map { jh =>
            jh.keyId.map(k => jwkProvider.get(k)).getOrElse {
              val errMsg = "Unable to retrieve kid from JWK"
              log.debug(errMsg)
              Future.successful(Failure(AuthenticationError(errMsg)))
            }
          }
          .getOrElse {
            val errMsg = "Unable to retrieve header from JWK"
            log.debug(errMsg)
            Future.successful(Failure(AuthenticationError(errMsg)))
          }
    }
  }

  private[this] def cleanToken(token: OAuth2BearerToken) = {
    log.trace("Stripping Bearer prefix from token...")
    token.value.stripPrefix("Bearer ")
  }

  /**
   * Validates the claims inside the token. 'isValid' checks the issuedAt,
   * expiresAt, issuer and audience fields.
   *
   * @param jwtClaim
   *   the [[JwtClaim]] to validate
   * @return
   *   a Try with the [[JwtClaim]] if successfully validated
   */
  private[this] def validateClaim(
      jwtClaim: JwtClaim
  )(implicit oidcConfig: OpenIdConnectConfig): Try[JwtClaim] = {
    log.trace("Validating jwt claim...")
    val allowDetailedLogging = oidcCfg.allowDetailedLogging
    if (jwtClaim.isValid(oidcConfig.issuer, audience)) {
      jwtClaim.logValidity(log, isValid = true, allowDetailedLogging)
      Success(jwtClaim)
    } else {
      jwtClaim.logValidity(log, isValid = false, allowDetailedLogging)
      Failure(AuthenticationError("The JWT is not valid"))
    }
  }

  private[this] def decodeToken(token: String, pubKey: PublicKey) = {
    JwtCirce.decode(token, pubKey).recoverWith {
      case ex: JwtException => Failure(InvalidTokenError(ex.getMessage))
      case ex               => Failure(ex)
    }
  }

  /**
   * Validates a JWT and potentially returns the claims if the token was
   * successfully parsed and validated
   *
   * @param bearerToken
   *   The JWT token
   * @return
   *   Eventually a [[JwtClaim]] if successfully decoded and validated
   */
  def validate(bearerToken: OAuth2BearerToken): Future[Try[JwtClaim]] = {
    val t = cleanToken(bearerToken)
    wellKnownOidcConfig
      .flatMap { implicit oidcCfg =>
        getJwk(t).map { tryJwk =>
          for {
            // Get the secret key for this token
            jwk <- tryJwk
            // generate public key
            pubKey <- Jwk.generatePublicKey(jwk)
            // Decode the token using the secret key
            claim <- decodeToken(t, pubKey)
            // validate the data stored inside the token
            validClaim <- validateClaim(claim)
          } yield validClaim
        }
      }
      .recoverWith { case t => Future.successful(Failure(t)) }
  }

  private[auth] def validateToken[A](bearerToken: OAuth2BearerToken)(
      valid: JwtClaim => A,
      invalid: ProxyAuthError => A,
      errorHandler: Throwable => A
  ): Future[A] = {
    validate(bearerToken).map {
      case Success(claim) => valid(claim)
      case Failure(err) =>
        err match {
          case ae: ProxyAuthError => invalid(ae)
          case ex =>
            log.warn("Error connecting to OpenID Connect server", ex)
            errorHandler(ex)
        }
    }
  }

  // scalastyle:off
  private[auth] def wellKnownOidcConfig: Future[OpenIdConnectConfig] = {
    log.debug(s"Fetching openid-connect config from $oidcWellKnown...")

    Http()
      .singleRequest(HttpRequest(uri = oidcWellKnown))
      .recover { case ste: StreamTcpException =>
        // Handle any HTTP/TCP errors
        log.error(s"Error fetching config from $oidcWellKnown", ste)
        throw OpenIdConnectError(
          message = "OpenID Connect server does not seem to be available.",
          cause = Option(ste.getCause)
        )
      }
      .flatMap { res =>
        log.info(s"OpenID well-known request status: ${res.status}")
        res match {
          case HttpResponse(StatusCodes.OK, _, body, _) =>
            foldBody(body.dataBytes).map {
              case Some(bodyStr) =>
                val js = parse(bodyStr).toOption.getOrElse(Json.Null)
                log.trace(
                  s"OpenID well-known response body:\n${js.spaces2}"
                )
                js.as[OpenIdConnectConfig].toOption

              case None =>
                log.info(s"OpenID well-known request returned no body")
                None
            }

          case HttpResponse(status, _, body, _) =>
            foldBody(body.dataBytes).map {
              case Some(bodyStr) =>
                log.whenTraceEnabled {
                  val js = parse(bodyStr).toOption.getOrElse(Json.Null)
                  log.trace(
                    s"OpenID well-known response returned with status" +
                      s" ${status.intValue()} and body:\n${js.spaces2}"
                  )
                }
                None

              case None =>
                log.info(s"OpenID well-known request returned no body")
                None
            }
        }
      }
      .map { maybeCfg =>
        maybeCfg.orThrow(OpenIdConnectError(s"Could not load $oidcWellKnown"))
      }
  }
  // scalastyle:on

  // FOR USE IN TESTS!
  def generateToken(
      clientId: String,
      clientSecret: String,
      audience: String,
      grantType: String
  ): Future[Option[AccessToken]] = {
    log.debug(s"Generating token for $clientId...")
    wellKnownOidcConfig.flatMap { oidcCfg =>
      val req = TokenRequest(clientId, clientSecret, audience, grantType)

      Http().singleRequest(req.request(oidcCfg.tokenEndpoint)).flatMap { res =>
        log.info(s"Token request status: ${res.status}")
        log.trace(
          "Token response headers: [" +
            res.headers.mkString("\n  ", "\n  ", "\n") +
            s"]"
        )

        res match {
          case HttpResponse(StatusCodes.OK, _, body, _) =>
            foldBody(body.dataBytes).map { ms =>
              ms.flatMap { bodyStr =>
                val js = parse(bodyStr).toOption.getOrElse(Json.Null)
                log.trace(s"Token response body:\n${js.spaces2}")
                js.as[AccessToken].toOption
              }.orElse {
                log.info(s"Token request returned no body")
                None
              }
            }

          case HttpResponse(_, _, body, _) =>
            foldBody(body.dataBytes).map {
              case Some(bodyStr) =>
                log.whenTraceEnabled {
                  val js = parse(bodyStr).toOption.getOrElse(Json.Null)
                  log.trace(s"Token response body:\n${js.spaces2}")
                }
                None

              case None =>
                log.info(s"Token request returned no body")
                None
            }
        }
      }
    }
  }
}

object OpenIdClient {

  def apply(cfg: AppCfg)(implicit mat: Materializer): OpenIdClient = {
    val oidcCfg = cfg.server.openidConnect.getUnsafe
    new OpenIdClient(oidcCfg, enforceHttps = oidcCfg.requireHttps)
  }

  def apply(
      oidcCfg: OpenIdConnectCfg,
      enforceHttps: Boolean = true
  )(
      implicit mat: Materializer
  ): OpenIdClient = new OpenIdClient(oidcCfg, enforceHttps)
}
