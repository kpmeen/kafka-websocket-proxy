package net.scalytica.kafka.wsproxy.web

import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import net.scalytica.kafka.wsproxy.auth.OpenIdClient
import net.scalytica.kafka.wsproxy.avro.SchemaTypes.{
  AvroCommit,
  AvroConsumerRecord,
  AvroProducerRecord,
  AvroProducerResult
}
import net.scalytica.kafka.wsproxy.config.Configuration.AppCfg
import org.apache.avro.Schema

trait SchemaRoutes { self: BaseRoutes =>

  private[this] def avroSchemaString(schema: Schema): ToResponseMarshallable = {
    HttpEntity(
      contentType = ContentTypes.`application/json`,
      string = schema.toString(true)
    )
  }

  def schemaRoutes(
      implicit cfg: AppCfg,
      maybeOpenIdClient: Option[OpenIdClient]
  ): Route = {
    extractMaterializer { implicit mat =>
      pathPrefix("schemas") {
        pathPrefix("avro") {
          pathPrefix("producer") {
            path("record") {
              maybeAuthenticate(cfg, maybeOpenIdClient, mat) { _ =>
                complete(avroSchemaString(AvroProducerRecord.schema))
              }
            } ~ path("result") {
              maybeAuthenticate(cfg, maybeOpenIdClient, mat) { _ =>
                complete(avroSchemaString(AvroProducerResult.schema))
              }
            }
          } ~ pathPrefix("consumer") {
            path("record") {
              maybeAuthenticate(cfg, maybeOpenIdClient, mat) { _ =>
                complete(avroSchemaString(AvroConsumerRecord.schema))
              }
            } ~ path("commit") {
              maybeAuthenticate(cfg, maybeOpenIdClient, mat) { _ =>
                complete(avroSchemaString(AvroCommit.schema))
              }
            }
          }
        }
      }
    }
  }

}
