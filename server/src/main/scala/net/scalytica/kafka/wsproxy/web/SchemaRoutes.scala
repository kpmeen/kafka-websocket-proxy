package net.scalytica.kafka.wsproxy.web

import org.apache.pekko.http.scaladsl.marshalling.ToResponseMarshallable
import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server._
import net.scalytica.kafka.wsproxy.auth.OpenIdClient
import net.scalytica.kafka.wsproxy.config.Configuration.AppCfg

trait SchemaRoutes { self: BaseRoutes =>

  private[this] def avroNotAvailable(): ToResponseMarshallable = {
    HttpEntity(
      contentType = ContentTypes.`application/json`,
      string =
        "Kafka WebSocket Proxy does not implement an Avro protocol any more."
    )
  }

  def schemaRoutes(
      implicit cfg: AppCfg,
      maybeOpenIdClient: Option[OpenIdClient]
  ): Route = {
    extractMaterializer { implicit mat =>
      maybeAuthenticate(cfg, maybeOpenIdClient, mat) { _ =>
        pathPrefix("schemas") {
          pathPrefix("avro") {
            pathPrefix("producer") {
              path("record") {
                get {
                  complete(avroNotAvailable())
                }
              } ~ path("result") {
                get {
                  complete(avroNotAvailable())
                }
              }
            } ~ pathPrefix("consumer") {
              path("record") {
                get {
                  complete(avroNotAvailable())
                }
              } ~ path("commit") {
                get {
                  complete(avroNotAvailable())
                }
              }
            }
          }
        }
      }
    }
  }

}
