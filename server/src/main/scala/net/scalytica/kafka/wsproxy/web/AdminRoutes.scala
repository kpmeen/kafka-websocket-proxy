package net.scalytica.kafka.wsproxy.web

import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import io.circe.Printer
import io.circe.syntax._
import net.scalytica.kafka.wsproxy.admin.WsKafkaAdminClient
import net.scalytica.kafka.wsproxy.auth.OpenIdClient
import net.scalytica.kafka.wsproxy.codecs.Encoders.brokerInfoEncoder
import net.scalytica.kafka.wsproxy.config.Configuration.AppCfg

/** Administrative endpoints */
trait AdminRoutes { self: BaseRoutes =>

  private[this] def serveClusterInfo(implicit cfg: AppCfg) = {
    complete {
      val admin = new WsKafkaAdminClient(cfg)
      try {
        log.debug("Fetching Kafka cluster info...")
        val ci = admin.clusterInfo

        HttpResponse(
          status = OK,
          entity = HttpEntity(
            contentType = ContentTypes.`application/json`,
            string = ci.asJson.printWith(Printer.spaces2)
          )
        )
      } finally {
        admin.close()
      }
    }
  }

  def adminRoutes(
      implicit cfg: AppCfg,
      maybeOidcClient: Option[OpenIdClient],
      sys: ActorSystem
  ): Route = {
    extractMaterializer { implicit mat =>
      pathPrefix("admin") {
        pathPrefix("kafka") {
          path("info") {
            maybeAuthenticate(cfg, maybeOidcClient, mat)(_ => serveClusterInfo)
          }
        }
      }
    }
  }
}
