package net.scalytica.kafka.wsproxy.auth

import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.Accept
import akka.stream.Materializer
import io.circe.generic.extras.auto._
import io.circe.parser._
import net.scalytica.kafka.wsproxy.StringExtensions
import net.scalytica.kafka.wsproxy.errors.SigningKeyNotFoundError
import net.scalytica.kafka.wsproxy.logging.WithProxyLogger
import net.scalytica.kafka.wsproxy.utils.HostResolver

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

/**
 * JWK provider based on the {{com.auth0.jwk.UrlJwkProvider}}. Intention here is
 * to provide an async Scala based akka-http alternative without all the
 * exceptions being thrown around. It also uses circe for parsing the JSON
 * responses when loading the JWK config.
 *
 * @param url
 *   The host URL where the JWK config can be found.
 *
 * @see
 *   https://tools.ietf.org/html/rfc7517
 */
class UrlJwkProvider private[auth] (url: String, enforceHttps: Boolean = true)
    extends WithProxyLogger {

  require(url.safeNonEmpty, "The URL string cannot be empty")
  if (enforceHttps) {
    require(
      url.startsWith("https://"),
      "The URL must be prefixed with https://"
    )
  }

  val host = url
    .stripPrefix("https://")
    .stripPrefix("http://")
    .takeWhile(c => c != ':' && c != '/')

  require(host.nonEmpty, "The URL must contain a valid host")
  require(HostResolver.resolveHost(host).isSuccess, s"Could not resolve $host")

  /** Load the JWK configuration from the provided URL */
  private[auth] def load()(implicit mat: Materializer): Future[List[Jwk]] = {
    implicit val as = mat.system
    implicit val ec = mat.executionContext

    val request = HttpRequest(method = HttpMethods.GET, uri = url)
      .withHeaders(Accept(MediaTypes.`application/json`))

    Http().singleRequest(request).flatMap { res =>
      logger.info(s"JWK config request status: ${res.status}")
      res match {
        case HttpResponse(StatusCodes.OK, _, body, _) =>
          foldBody(body.dataBytes).map {
            case None => List.empty
            case Some(bodyStr) =>
              val jsObjs = parse(bodyStr).toOption
                .map(_ \\ "keys")
                .flatMap(_.headOption)
                .flatMap(_.asArray.map(_.toList))
                .getOrElse(List.empty)

              jsObjs
                .map(js => js.as[Jwk])
                .map {
                  case ok @ Right(jwk) =>
                    logger.trace(s"Successfully parsed JWK object $jwk")
                    ok
                  case ko @ Left(err) =>
                    logger.error(s"Error parsing JWK object", err)
                    ko
                }
                .collect { case Right(jwk) => jwk }
          }
        case _ =>
          logger.info("JWK config could not be found.")
          Future.successful(List.empty)
      }
    }
  }

  /**
   * Try to fetch the provided {{keyId}} from the JWK config
   *
   * @param keyId
   *   The JWK {{kid}} to lookup
   * @param mat
   *   Implicitly provided [[Materializer]]
   * @return
   *   Eventually returns a Try containing the [[Jwk]] that was found
   */
  def get(keyId: String)(implicit mat: Materializer): Future[Try[Jwk]] = {
    implicit val ec = mat.executionContext

    load().map { keys =>
      logger.trace(
        s"Trying to find keyId $keyId in keys:${keys.mkString("\n", "\n", "")}"
      )
      keys.find(_.kid.exists(_.equalsIgnoreCase(keyId))) match {
        case Some(key) => Success(key)
        case None =>
          Failure(SigningKeyNotFoundError(s"No key found at $url"))
      }
    }
  }
}

object UrlJwkProvider {

  def apply(url: String, enforceHttps: Boolean = true): UrlJwkProvider =
    new UrlJwkProvider(url, enforceHttps)

}
