package net.scalytica.kafka.wsproxy.web.admin

import net.scalytica.kafka.wsproxy.config.DynamicConfigHandlerProtocol._
import net.scalytica.kafka.wsproxy.web.BaseRoutes

import org.apache.pekko.http.scaladsl.model.ContentTypes.`application/json`
import org.apache.pekko.http.scaladsl.model.HttpEntity
import org.apache.pekko.http.scaladsl.model.HttpResponse
import org.apache.pekko.http.scaladsl.model.StatusCode
import org.apache.pekko.http.scaladsl.model.StatusCodes.BadRequest
import org.apache.pekko.http.scaladsl.model.StatusCodes.InternalServerError
import org.apache.pekko.http.scaladsl.model.StatusCodes.OK

/**
 * Response definitions to be used by the endpoints defined in [[AdminRoutes]]
 */
trait AdminRoutesResponses { self: BaseRoutes =>

  /**
   * Build an empty [[HttpResponse]] with the given [[StatusCode]]
   * @param status
   *   The [[StatusCode]] to use
   * @return
   *   A [[HttpResponse]]
   */
  protected def emptyResponse(status: StatusCode): HttpResponse =
    HttpResponse(
      status = status,
      entity = HttpEntity.empty(`application/json`)
    )

  /**
   * Build a [[HttpResponse]] with the given [[StatusCode]] and json String
   * @param status
   *   The [[StatusCode]] to use
   * @param json
   *   String containing the JSON message to use as response body.
   * @return
   *   A [[HttpResponse]]
   */
  protected def response(status: StatusCode, json: String): HttpResponse = {
    HttpResponse(
      status = status,
      entity = HttpEntity(contentType = `application/json`, string = json)
    )
  }

  /**
   * Builds a [[HttpResponse]] where the status is OK, and the body is the
   * provided JSON string.
   * @param json
   *   String containing the JSON to use as response body.
   * @return
   *   A [[HttpResponse]]
   */
  protected def okResponse(json: String): HttpResponse =
    response(OK, json)

  /**
   * Function for building the response for invalid requests.
   *
   * @param msg
   *   String to serialise into JSON for the response body.
   * @param statusCode
   *   The [[StatusCode]] to use for the response.
   * @return
   *   A [[HttpResponse]]
   */
  protected def invalidRequestResponse(
      msg: String,
      statusCode: StatusCode = BadRequest
  ): HttpResponse =
    response(statusCode, jsonMessageFromString(msg))

  /** Static response definition for operations that time out. */
  protected lazy val timeoutResponse: HttpResponse =
    invalidRequestResponse("The operation timed out.", InternalServerError)

  /** Helper function to handler errors when fetching all dynamic configs */
  protected def handlerResRecovery(
      id: Option[String] = None
  ): PartialFunction[Throwable, DynamicConfigOpResult] = { case t: Throwable =>
    val msg = id
      .map(str => s"An error occurred while fetching dynamic configs for $str")
      .getOrElse("An error occurred while fetching dynamic configs.")
    log.error(msg, t)
    IncompleteOp(t.getMessage)
  }

  /** Helper function to handler errors when fetching specific dynamic config */
  protected def optRecovery[U](
      id: Option[String] = None
  ): PartialFunction[Throwable, Option[U]] = { case t: Throwable =>
    val msg = id
      .map(str => s"An error occurred while fetching dynamic configs for $str")
      .getOrElse("An error occurred while fetching dynamic configs.")
    log.error(msg, t)
    None
  }
}
