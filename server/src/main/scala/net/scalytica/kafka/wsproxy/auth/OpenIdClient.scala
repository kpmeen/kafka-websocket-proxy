package net.scalytica.kafka.wsproxy.auth

import java.time.Clock

import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.stream.Materializer
import io.circe._
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.auto._
import io.circe.parser._
import net.scalytica.kafka.wsproxy.config.Configuration.{
  AppCfg,
  OpenIdConnectCfg
}
import net.scalytica.kafka.wsproxy.OptionExtensions
import net.scalytica.kafka.wsproxy.errors.{
  AuthenticationError,
  OpenIdConnectError
}
import net.scalytica.kafka.wsproxy.logging.WithProxyLogger
import pdi.jwt.{JwtAlgorithm, JwtBase64, JwtCirce, JwtClaim}

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
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

  implicit private[this] val as = mat.system
  implicit private[this] val ec = mat.executionContext

  implicit val circeConfig: Configuration =
    Configuration.default.withSnakeCaseMemberNames

  implicit private[this] val clock = Clock.systemUTC()

  private[this] val awaitDuration = 10 seconds

  // A regex that defines the JWT pattern and allows us to
  // extract the header, claims and signature
  private[this] val jwtRegex      = """(.+?)\.(.+?)\.(.+?)""".r
  private[this] val audience      = oidcCfg.audience.getUnsafe
  private[this] val oidcWellKnown = oidcCfg.wellKnownUrl.getUnsafe

  private[this] val oidcConfiguration = wellKnownOidcConfig

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

  private[this] def getJwk(token: String): Future[Try[Jwk]] = {
    Future.fromTry((splitToken andThen decodeElements)(token)).flatMap {
      case (header, _, _) =>
        val jwtHeader = Try(JwtCirce.parseHeader(header)).toOption
        val jwkProvider =
          UrlJwkProvider(oidcConfiguration.jwksUri, enforceHttps)

        // Use jwkProvider to load the JWK data and return the JWK
        jwtHeader
          .map { jh =>
            jh.keyId.map(k => jwkProvider.get(k)).getOrElse {
              val errMsg = "Unable to retrieve kid from JWK"
              logger.debug(errMsg)
              Future.successful(Failure(AuthenticationError(errMsg)))
            }
          }
          .getOrElse {
            val errMsg = "Unable to retrieve header from JWK"
            logger.debug(errMsg)
            Future.successful(Failure(AuthenticationError(errMsg)))
          }
    }
  }

  private[this] def cleanToken(token: OAuth2BearerToken) = {
    logger.trace("Stripping Bearer prefix from token...")
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
  private[this] def validateClaims(jwtClaim: JwtClaim): Try[JwtClaim] = {
    logger.trace("Validating jwt claim...")
    if (jwtClaim.isValid(oidcConfiguration.issuer, audience)) {
      logger.trace("Jwt claim is valid!")
      Success(jwtClaim)
    } else {
      logger.trace("Jwt claim is NOT valid!")
      Failure(AuthenticationError("The JWT is not valid"))
    }
  }

  /**
   * Validates a JWT and potentially returns the claims if the token was
   * successfully parsed and validated
   *
   * @param bearerToken
   *   The JWT token String
   * @return
   *   Eventually a [[JwtClaim]] if successfully decoded and validated
   */
  def validate(bearerToken: OAuth2BearerToken): Future[Try[JwtClaim]] = {
    val t = cleanToken(bearerToken)
    getJwk(t).map { tryJwk =>
      for {
        // Get the secret key for this token
        jwk <- tryJwk
        // generate public key
        pubKey <- Jwk.generatePublicKey(jwk)
        // Decode the token using the secret key
        claims <- JwtCirce.decode(t, pubKey, Seq(JwtAlgorithm.RS256))
        // validate the data stored inside the token
        _ <- validateClaims(claims)
      } yield claims
    }
  }

  private[auth] def wellKnownOidcConfig: OpenIdConnectConfig = {
    logger.debug(s"Fetching openid-connect config from $oidcWellKnown...")
    val eventuallyCfg =
      Http().singleRequest(HttpRequest(uri = oidcWellKnown)).flatMap { res =>
        logger.info(s"OpenID well-known request status: ${res.status}")
        res match {
          case HttpResponse(StatusCodes.OK, _, body, _) =>
            foldBody(body.dataBytes).map {
              case Some(bodyStr) =>
                val js = parse(bodyStr).toOption.getOrElse(Json.Null)
                logger.trace(s"OpenID well-known response body:\n${js.spaces2}")
                js.as[OpenIdConnectConfig].toOption

              case None =>
                logger.info(s"OpenID well-known request returned no body")
                None
            }

          case HttpResponse(status, _, body, _) =>
            foldBody(body.dataBytes).map {
              case Some(bodyStr) =>
                logger.whenTraceEnabled {
                  val js = parse(bodyStr).toOption.getOrElse(Json.Null)
                  logger.trace(
                    s"OpenID well-known response returned with status" +
                      s" ${status.intValue()} and body:\n${js.spaces2}"
                  )
                }
                None

              case None =>
                logger.info(s"OpenID well-known request returned no body")
                None
            }
        }
      }
    Await.result(
      eventuallyCfg.map { cfg =>
        cfg.orThrow(
          OpenIdConnectError(
            s"Could not load ${oidcCfg.wellKnownUrl.getOrElse("")}"
          )
        )
      },
      awaitDuration
    )
  }

  // FOR USE IN TESTS!
  def generateToken(
      clientId: String,
      clientSecret: String,
      audience: String,
      grantType: String
  ): Future[Option[AccessToken]] = {
    val url = oidcConfiguration.tokenEndpoint
    val req = TokenRequest(clientId, clientSecret, audience, grantType)

    Http().singleRequest(req.request(url)).flatMap { res =>
      logger.info(s"Token request status: ${res.status}")
      logger.trace(
        "Token response headers: [" +
          res.headers.mkString("\n  ", "\n  ", "\n") +
          s"]"
      )

      res match {
        case HttpResponse(StatusCodes.OK, _, body, _) =>
          foldBody(body.dataBytes).map { ms =>
            ms.flatMap { bodyStr =>
              val js = parse(bodyStr).toOption.getOrElse(Json.Null)
              logger.trace(s"Token response body:\n${js.spaces2}")
              js.as[AccessToken].toOption
            }.orElse {
              logger.info(s"Token request returned no body")
              None
            }
          }

        case HttpResponse(_, _, body, _) =>
          foldBody(body.dataBytes).map {
            case Some(bodyStr) =>
              logger.whenTraceEnabled {
                val js = parse(bodyStr).toOption.getOrElse(Json.Null)
                logger.trace(s"Token response body:\n${js.spaces2}")
              }
              None

            case None =>
              logger.info(s"Token request returned no body")
              None
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
