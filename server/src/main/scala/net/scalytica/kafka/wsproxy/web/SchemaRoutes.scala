package net.scalytica.kafka.wsproxy.web

import org.apache.pekko.http.scaladsl.marshalling.ToResponseMarshallable
import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server._
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
      maybeAuthenticate(cfg, maybeOpenIdClient, mat) { _ =>
        pathPrefix("schemas") {
          pathPrefix("avro") {
            pathPrefix("producer") {
              path("record") {
                get {
                  complete(avroSchemaString(AvroProducerRecord.schema))
                }
              } ~ path("result") {
                get {
                  complete(avroSchemaString(AvroProducerResult.schema))
                }
              }
            } ~ pathPrefix("consumer") {
              path("record") {
                get {
                  complete(avroSchemaString(AvroConsumerRecord.schema))
                }
              } ~ path("commit") {
                get {
                  complete(avroSchemaString(AvroCommit.schema))
                }
              }
            }
          }
        }
      }
    }
  }

}
