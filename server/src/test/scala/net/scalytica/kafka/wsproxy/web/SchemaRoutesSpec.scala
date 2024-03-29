package net.scalytica.kafka.wsproxy.web

import org.apache.pekko.http.scaladsl.model.StatusCodes._
import org.apache.pekko.http.scaladsl.server._
import org.apache.pekko.http.scaladsl.testkit.RouteTestTimeout
import io.circe.Json
import net.scalytica.kafka.wsproxy.auth.OpenIdClient
import net.scalytica.kafka.wsproxy.config.Configuration.AppCfg
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
    with WsProxySpec {

  override protected val testTopicPrefix: String = "schema-routes-test-topic"

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(2, Minutes))

  implicit val timeout: RouteTestTimeout = RouteTestTimeout(20 seconds)

  implicit val oidClient: Option[OpenIdClient] = None

  val NoAvroSupportStr =
    "Kafka WebSocket Proxy does not implement an Avro protocol any more."

  "The schema routes" should {
    "return HTTP 404 when requesting an invalid resource" in {
      implicit val cfg: AppCfg = plainTestConfig()

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
      implicit val cfg: AppCfg = plainTestConfig()

      Get("/schemas/avro/producer/record") ~>
        Route.seal(schemaRoutes) ~>
        check {
          status mustBe OK
          responseAs[String] mustBe NoAvroSupportStr
        }
    }

    "return the Avro schema for producer results" in {
      implicit val cfg: AppCfg = plainTestConfig()

      Get("/schemas/avro/producer/result") ~>
        Route.seal(schemaRoutes) ~>
        check {
          status mustBe OK
          responseAs[String] mustBe NoAvroSupportStr
        }
    }

    "return the Avro schema for consumer record" in {
      implicit val cfg: AppCfg = plainTestConfig()

      Get("/schemas/avro/consumer/record") ~>
        Route.seal(schemaRoutes) ~>
        check {
          status mustBe OK
          responseAs[String] mustBe NoAvroSupportStr
        }
    }

    "return the Avro schema for consumer commit" in {
      implicit val cfg: AppCfg = plainTestConfig()

      Get("/schemas/avro/consumer/commit") ~>
        Route.seal(schemaRoutes) ~>
        check {
          status mustBe OK
          responseAs[String] mustBe NoAvroSupportStr
        }
    }
  }
}
