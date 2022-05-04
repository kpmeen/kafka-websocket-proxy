package net.scalytica.kafka.wsproxy.web

import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import net.scalytica.kafka.wsproxy.auth.OpenIdClient
import net.scalytica.kafka.wsproxy.config.Configuration.AppCfg

/** Routes for verifying status and health for the proxy */
trait StatusRoutes { self: BaseRoutes =>

  private[this] def serveHealthCheck = {
    complete {
      HttpResponse(
        status = OK,
        entity = HttpEntity(
          contentType = ContentTypes.`application/json`,
          string = """{ "response": "I'm healthy" }"""
        )
      )
    }
  }

  def statusRoutes(
      implicit cfg: AppCfg,
      maybeOidcClient: Option[OpenIdClient]
  ): Route = {
    extractMaterializer { implicit mat =>
      path("healthcheck") {
        if (cfg.server.secureHealthCheckEndpoint) {
          maybeAuthenticate(cfg, maybeOidcClient, mat) { _ =>
            serveHealthCheck
          }
        } else {
          serveHealthCheck
        }
      }
    }
  }
}
