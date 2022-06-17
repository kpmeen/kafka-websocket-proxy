package net.scalytica.kafka.wsproxy.web

import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server._
import akka.http.scaladsl.testkit.RouteTestTimeout
import io.circe.Json
import net.scalytica.kafka.wsproxy.auth.OpenIdClient
import net.scalytica.kafka.wsproxy.avro.SchemaTypes.{
  AvroCommit,
  AvroConsumerRecord,
  AvroProducerRecord,
  AvroProducerResult
}
import net.scalytica.test._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Minutes, Span}
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{EitherValues, OptionValues}

import scala.concurrent.duration._

class SchemaRoutesSpec
    extends AnyWordSpec
    with TestSchemaRoutes
    with EitherValues
    with OptionValues
    with ScalaFutures
    with WsProxyKafkaSpec {

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(2, Minutes))

  implicit val timeout = RouteTestTimeout(20 seconds)

  implicit val oidClient: Option[OpenIdClient] = None

  "The schema routes" should {
    "return HTTP 404 when requesting an invalid resource" in {
      implicit val cfg = plainTestConfig()

      val expected = Json
        .obj(
          "message" -> Json
            .fromString("This is not the resource you are looking for.")
        )
        .spaces2

      Get() ~> Route.seal(schemaRoutes) ~> check {
        status mustBe NotFound
        responseAs[String] mustBe expected
      }
    }

    "return the Avro schema for producer records" in {
      implicit val cfg = plainTestConfig()

      Get("/schemas/avro/producer/record") ~>
        Route.seal(schemaRoutes) ~>
        check {
          status mustBe OK
          responseAs[String] mustBe AvroProducerRecord.schema.toString(true)
        }
    }

    "return the Avro schema for producer results" in {
      implicit val cfg = plainTestConfig()

      Get("/schemas/avro/producer/result") ~>
        Route.seal(schemaRoutes) ~>
        check {
          status mustBe OK
          responseAs[String] mustBe AvroProducerResult.schema.toString(true)
        }
    }

    "return the Avro schema for consumer record" in {
      implicit val cfg = plainTestConfig()

      Get("/schemas/avro/consumer/record") ~>
        Route.seal(schemaRoutes) ~>
        check {
          status mustBe OK
          responseAs[String] mustBe AvroConsumerRecord.schema.toString(true)
        }
    }

    "return the Avro schema for consumer commit" in {
      implicit val cfg = plainTestConfig()

      Get("/schemas/avro/consumer/commit") ~>
        Route.seal(schemaRoutes) ~>
        check {
          status mustBe OK
          responseAs[String] mustBe AvroCommit.schema.toString(true)
        }
    }
  }
}
