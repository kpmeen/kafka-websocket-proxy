package net.scalytica.kafka.wsproxy.web.admin

import io.circe.parser._
import io.circe.syntax._
import net.scalytica.kafka.wsproxy.codecs.Decoders.{
  brokerInfoDecoder,
  dynamicCfgDecoder
}
import net.scalytica.kafka.wsproxy.codecs.Encoders.dynamicCfgEncoder
import net.scalytica.kafka.wsproxy.config.Configuration.{
  ConsumerSpecificLimitCfg,
  DynamicCfg,
  ProducerSpecificLimitCfg
}
import net.scalytica.kafka.wsproxy.models.BrokerInfo
import net.scalytica.test.TestDataGenerators._
import net.scalytica.test._
import org.apache.pekko.http.scaladsl.model.ContentTypes._
import org.apache.pekko.http.scaladsl.model.StatusCodes._
import org.apache.pekko.http.scaladsl.model.headers.OAuth2BearerToken
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity}
import org.apache.pekko.http.scaladsl.server._
import org.apache.pekko.http.scaladsl.testkit.RouteTestTimeout
import org.scalatest.time.{Minutes, Span}

import scala.concurrent.duration._

// scalastyle:off magic.number
class AdminRoutesOpenIdSpec extends BaseAdminRoutesSpec with MockOpenIdServer {

  override protected val testTopicPrefix: String = "admin-openid-test-topic"

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(3, Minutes))

  implicit val timeout: RouteTestTimeout = RouteTestTimeout(20 seconds)

  "Having the proxy configured with OpenID" when {

    "using the Kafka cluster info routes" should {

      "successfully return the result" in
        withOpenIdConnectServerAndToken(useJwtCreds = false) {
          case (_, _, oidcClient, oidcCfg, token) =>
            withAdminContext(optOpenIdCfg = Option(oidcCfg)) {
              case (kcfg, cfg, sessionRef, dynCfgRef, _) =>
                Get("/admin/kafka/info") ~>
                  addCredentials(token.bearerToken) ~>
                  Route.seal(
                    adminRoutes(cfg, sessionRef, dynCfgRef, Some(oidcClient))
                  ) ~>
                  check {
                    status mustBe OK
                    responseEntity.contentType mustBe `application/json`

                    val ci = parse(responseAs[String])
                      .map(_.as[Seq[BrokerInfo]])
                      .flatMap(identity)
                      .rightValue

                    ci must have size 1
                    ci.headOption.value mustBe BrokerInfo(
                      id = 0,
                      host = "localhost",
                      port = kcfg.kafkaPort,
                      rack = None
                    )
                  }
            }
        }

      "return 401 when the bearer token is invalid" in
        withOpenIdConnectServerAndClient(useJwtCreds = false) {
          case (_, _, oidcClient, oidcCfg) =>
            withAdminContext(optOpenIdCfg = Option(oidcCfg)) {
              case (_, cfg, sessionRef, dynCfgRef, _) =>
                Get("/admin/kafka/info") ~>
                  addCredentials(OAuth2BearerToken("invalid-token")) ~>
                  Route.seal(
                    adminRoutes(cfg, sessionRef, dynCfgRef, Some(oidcClient))
                  ) ~>
                  check {
                    status mustBe Unauthorized
                  }
            }
        }
    }

    "using client config routes when dynamic configs are not enabled" should {

      "only return all static client configs" in
        withOpenIdConnectServerAndToken(useJwtCreds = false) {
          case (_, _, oidcClient, oidcCfg, token) =>
            withAdminContext(optOpenIdCfg = Option(oidcCfg)) {
              case (_, cfg, sessionRef, dynCfgRef, _) =>
                Get("/admin/client/config") ~>
                  addCredentials(token.bearerToken) ~>
                  Route.seal(
                    adminRoutes(cfg, sessionRef, dynCfgRef, Some(oidcClient))
                  ) ~>
                  check {
                    status mustBe OK
                    responseEntity.contentType mustBe `application/json`
                    val resStr = responseAs[String]
                    val json   = parse(resStr).rightValue
                    val cursor = json.hcursor

                    assertAllStaticConfigs(cursor)
                    assertAllDynamicConfigs(
                      expected = Seq.empty,
                      cursor =
                        cursor.downField("consumers").downField("dynamic")
                    )
                    assertAllDynamicConfigs(
                      expected = Seq.empty,
                      cursor =
                        cursor.downField("producers").downField("dynamic")
                    )
                  }
            }
        }

      "return the static config for a specific consumer when it exists" in
        withOpenIdConnectServerAndToken(useJwtCreds = false) {
          case (_, _, oidcClient, oidcCfg, token) =>
            withAdminContext(optOpenIdCfg = Option(oidcCfg)) {
              case (_, cfg, sessionRef, dynCfgRef, _) =>
                Get("/admin/client/config/consumer/dummy") ~>
                  addCredentials(token.bearerToken) ~>
                  Route.seal(
                    adminRoutes(cfg, sessionRef, dynCfgRef, Some(oidcClient))
                  ) ~>
                  check {
                    status mustBe OK
                    responseEntity.contentType mustBe `application/json`
                    val resStr = responseAs[String]
                    val res = parse(resStr).rightValue.as[DynamicCfg].rightValue
                    assertConsumerConfig(ExpStaticCons2, res)
                  }
            }
        }

      "return the static config for a specific producer when it exists" in
        withOpenIdConnectServerAndToken(useJwtCreds = false) {
          case (_, _, oidcClient, oidcCfg, token) =>
            withAdminContext(optOpenIdCfg = Option(oidcCfg)) {
              case (_, cfg, sessionRef, dynCfgRef, _) =>
                Get("/admin/client/config/producer/limit-test-producer-2") ~>
                  addCredentials(token.bearerToken) ~>
                  Route.seal(
                    adminRoutes(cfg, sessionRef, dynCfgRef, Some(oidcClient))
                  ) ~>
                  check {
                    status mustBe OK
                    responseEntity.contentType mustBe `application/json`
                    val resStr = responseAs[String]
                    val res = parse(resStr).rightValue.as[DynamicCfg].rightValue
                    assertProducerConfig(ExpStaticProd3, res)
                  }
            }
        }

      "return 405 when trying to add new dynamic configs" in
        withOpenIdConnectServerAndToken(useJwtCreds = false) {
          case (_, _, oidcClient, oidcCfg, token) =>
            withAdminContext(optOpenIdCfg = Option(oidcCfg)) {
              case (_, cfg, sessionRef, dynCfgRef, _) =>
                val cconf = createConsumerCfg("my-consumer", Some(10), Some(2))
                val json  = (cconf: DynamicCfg).asJson.spaces2

                val entity = HttpEntity(ContentTypes.`application/json`, json)

                Post("/admin/client/config/consumer", entity) ~>
                  addCredentials(token.bearerToken) ~>
                  Route.seal(
                    adminRoutes(cfg, sessionRef, dynCfgRef, Some(oidcClient))
                  ) ~>
                  check {
                    status mustBe MethodNotAllowed
                    responseEntity.contentType mustBe `application/json`
                  }
            }
        }

      "return 405 when trying to remove a dynamic config" in
        withOpenIdConnectServerAndToken(useJwtCreds = false) {
          case (_, _, oidcClient, oidcCfg, token) =>
            withAdminContext(optOpenIdCfg = Option(oidcCfg)) {
              case (_, cfg, sessionRef, dynCfgRef, _) =>
                Delete("/admin/client/config/consumer/my-consumer") ~>
                  addCredentials(token.bearerToken) ~>
                  Route.seal(
                    adminRoutes(cfg, sessionRef, dynCfgRef, Some(oidcClient))
                  ) ~>
                  check {
                    status mustBe MethodNotAllowed
                    responseEntity.contentType mustBe `application/json`
                  }
            }
        }

      "return 405 when trying to remove all dynamic configs" in
        withOpenIdConnectServerAndToken(useJwtCreds = false) {
          case (_, _, oidcClient, oidcCfg, token) =>
            withAdminContext(optOpenIdCfg = Option(oidcCfg)) {
              case (_, cfg, sessionRef, dynCfgRef, _) =>
                Delete("/admin/client/config") ~>
                  addCredentials(token.bearerToken) ~>
                  Route.seal(
                    adminRoutes(cfg, sessionRef, dynCfgRef, Some(oidcClient))
                  ) ~>
                  check {
                    status mustBe MethodNotAllowed
                    responseEntity.contentType mustBe `application/json`
                  }
            }
        }
    }

    "using client config routes when dynamic configs are enabled" should {

      "successfully add a new dynamic consumer client config" in
        withOpenIdConnectServerAndToken(useJwtCreds = false) {
          case (_, _, oidcClient, oidcCfg, token) =>
            withAdminContext(
              useDynamicConfigs = true,
              optOpenIdCfg = Option(oidcCfg)
            ) { case (_, cfg, sessionRef, dynCfgRef, _) =>
              val route =
                Route.seal(
                  adminRoutes(cfg, sessionRef, dynCfgRef, Some(oidcClient))
                )
              val cconf = createConsumerCfg("my-consumer", Some(10), Some(2))

              assertPostConsumerConfig(cconf, route, Some(token.bearerToken))

              eventually {
                Get("/admin/client/config/consumer/my-consumer") ~>
                  addCredentials(token.bearerToken) ~>
                  route ~>
                  check {
                    status mustBe OK
                    responseEntity.contentType mustBe `application/json`
                    val resStr = responseAs[String]
                    val res =
                      parse(resStr).rightValue.as[DynamicCfg].rightValue
                    assertConsumerConfig(cconf, res)
                  }
              }
            }
        }

      "successfully add a new dynamic producer client config" in
        withOpenIdConnectServerAndToken(useJwtCreds = false) {
          case (_, _, oidcClient, oidcCfg, token) =>
            withAdminContext(
              useDynamicConfigs = true,
              optOpenIdCfg = Option(oidcCfg)
            ) { case (_, cfg, sessionRef, dynCfgRef, _) =>
              val route =
                Route.seal(
                  adminRoutes(cfg, sessionRef, dynCfgRef, Some(oidcClient))
                )
              val pconf =
                createProducerCfg("my-producer-1", Some(10), Some(2))

              assertPostProducerConfig(pconf, route, Some(token.bearerToken))

              eventually {
                Get("/admin/client/config/producer/my-producer-1") ~>
                  addCredentials(token.bearerToken) ~>
                  route ~>
                  check {
                    status mustBe OK
                    responseEntity.contentType mustBe `application/json`
                    val resStr = responseAs[String]
                    val res =
                      parse(resStr).rightValue.as[DynamicCfg].rightValue
                    assertProducerConfig(pconf, res)
                  }
              }
            }

        }

      "successfully return all static and dynamic client configs" in
        withOpenIdConnectServerAndToken(useJwtCreds = false) {
          case (_, _, oidcClient, oidcCfg, token) =>
            withAdminContext(
              useDynamicConfigs = true,
              optOpenIdCfg = Option(oidcCfg),
              useFreshStateTopics = true
            ) { case (_, cfg, sessionRef, dynCfgRef, _) =>
              val route =
                Route.seal(
                  adminRoutes(cfg, sessionRef, dynCfgRef, Some(oidcClient))
                )

              val expDynConsCfgs =
                assertPostNConsumerConfigs(4, route, Some(token.bearerToken))
              val expDynProdCfgs =
                assertPostNProducerConfigs(4, route, Some(token.bearerToken))

              eventually {
                Get("/admin/client/config") ~>
                  addCredentials(token.bearerToken) ~>
                  route ~> check {
                    status mustBe OK
                    responseEntity.contentType mustBe `application/json`
                    val resStr = responseAs[String]
                    val json   = parse(resStr).rightValue
                    val cursor = json.hcursor

                    assertAllStaticConfigs(cursor)
                    assertAllDynamicConfigs(
                      expected = expDynConsCfgs,
                      cursor =
                        cursor.downField("consumers").downField("dynamic")
                    )
                    assertAllDynamicConfigs(
                      expected = expDynProdCfgs,
                      cursor =
                        cursor.downField("producers").downField("dynamic")
                    )
                  }
              }
            }
        }

      "successfully return a specific dynamic consumer client config" in
        withOpenIdConnectServerAndToken(useJwtCreds = false) {
          case (_, _, oidcClient, oidcCfg, token) =>
            withAdminContext(
              useDynamicConfigs = true,
              optOpenIdCfg = Option(oidcCfg)
            ) { case (_, cfg, sessionRef, dynCfgRef, _) =>
              val route =
                Route.seal(
                  adminRoutes(cfg, sessionRef, dynCfgRef, Some(oidcClient))
                )
              val expDynConsCfgs =
                assertPostNConsumerConfigs(3, route, Some(token.bearerToken))
              val exp =
                expDynConsCfgs.tail.head.asInstanceOf[ConsumerSpecificLimitCfg]

              eventually {
                Get(s"/admin/client/config/consumer/${exp.id}") ~>
                  addCredentials(token.bearerToken) ~>
                  route ~>
                  check {
                    status mustBe OK
                    responseEntity.contentType mustBe `application/json`
                    val resStr = responseAs[String]
                    val res =
                      parse(resStr).rightValue.as[DynamicCfg].rightValue
                    assertConsumerConfig(exp, res)
                  }
              }
            }
        }

      "successfully return a specific static consumer client config" in
        withOpenIdConnectServerAndToken(useJwtCreds = false) {
          case (_, _, oidcClient, oidcCfg, token) =>
            withAdminContext(
              useDynamicConfigs = true,
              optOpenIdCfg = Option(oidcCfg)
            ) { case (_, cfg, sessionRef, dynCfgRef, _) =>
              Get("/admin/client/config/consumer/dummy") ~>
                addCredentials(token.bearerToken) ~>
                Route.seal(
                  adminRoutes(cfg, sessionRef, dynCfgRef, Some(oidcClient))
                ) ~>
                check {
                  status mustBe OK
                  responseEntity.contentType mustBe `application/json`
                  val resStr = responseAs[String]
                  val res = parse(resStr).rightValue.as[DynamicCfg].rightValue
                  assertConsumerConfig(ExpStaticCons2, res)
                }
            }
        }

      "successfully return a specific dynamic producer client config" in
        withOpenIdConnectServerAndToken(useJwtCreds = false) {
          case (_, _, oidcClient, oidcCfg, token) =>
            withAdminContext(
              useDynamicConfigs = true,
              optOpenIdCfg = Option(oidcCfg)
            ) { case (_, cfg, sessionRef, dynCfgRef, _) =>
              val route =
                Route.seal(
                  adminRoutes(cfg, sessionRef, dynCfgRef, Some(oidcClient))
                )
              val expDynProdCfgs =
                assertPostNProducerConfigs(3, route, Some(token.bearerToken))
              val exp =
                expDynProdCfgs.tail.head.asInstanceOf[ProducerSpecificLimitCfg]

              eventually {
                Get(s"/admin/client/config/producer/${exp.id}") ~>
                  addCredentials(token.bearerToken) ~>
                  route ~>
                  check {
                    status mustBe OK
                    responseEntity.contentType mustBe `application/json`
                    val resStr = responseAs[String]
                    val res =
                      parse(resStr).rightValue.as[DynamicCfg].rightValue
                    assertProducerConfig(exp, res)
                  }
              }
            }
        }

      "successfully return a specific static producer client config" in
        withOpenIdConnectServerAndToken(useJwtCreds = false) {
          case (_, _, oidcClient, oidcCfg, token) =>
            withAdminContext(
              useDynamicConfigs = true,
              optOpenIdCfg = Option(oidcCfg)
            ) { case (_, cfg, sessionRef, dynCfgRef, _) =>
              Get("/admin/client/config/producer/limit-test-producer-2") ~>
                addCredentials(token.bearerToken) ~>
                Route.seal(
                  adminRoutes(cfg, sessionRef, dynCfgRef, Some(oidcClient))
                ) ~>
                check {
                  status mustBe OK
                  responseEntity.contentType mustBe `application/json`
                  val resStr = responseAs[String]
                  val res = parse(resStr).rightValue.as[DynamicCfg].rightValue
                  assertProducerConfig(ExpStaticProd3, res)
                }
            }
        }

      "successfully update an existing dynamic consumer client config" in
        withOpenIdConnectServerAndToken(useJwtCreds = false) {
          case (_, _, oidcClient, oidcCfg, token) =>
            withAdminContext(
              useDynamicConfigs = true,
              optOpenIdCfg = Option(oidcCfg)
            ) { case (_, cfg, sessionRef, dynCfgRef, _) =>
              val route =
                Route.seal(
                  adminRoutes(cfg, sessionRef, dynCfgRef, Some(oidcClient))
                )
              val expDynConsCfgs =
                assertPostNConsumerConfigs(3, route, Some(token.bearerToken))
              val exp =
                expDynConsCfgs.tail.head.asInstanceOf[ConsumerSpecificLimitCfg]

              eventually {
                Get(s"/admin/client/config/consumer/${exp.id}") ~>
                  addCredentials(token.bearerToken) ~>
                  route ~>
                  check {
                    status mustBe OK
                    responseEntity.contentType mustBe `application/json`
                    val resStr = responseAs[String]
                    val res =
                      parse(resStr).rightValue.as[DynamicCfg].rightValue
                    assertConsumerConfig(exp, res)
                  }
              }

              val upd     = exp.copy(maxConnections = Some(5))
              val updJson = (upd: DynamicCfg).asJson.spaces2

              Put(s"/admin/client/config/consumer/${exp.id}", updJson) ~>
                addCredentials(token.bearerToken) ~>
                route ~>
                check {
                  status mustBe OK
                  responseEntity.contentType mustBe `application/json`
                }

              eventually {
                Get(s"/admin/client/config/consumer/${exp.id}") ~>
                  addCredentials(token.bearerToken) ~>
                  route ~>
                  check {
                    status mustBe OK
                    responseEntity.contentType mustBe `application/json`
                    val resStr = responseAs[String]
                    val res =
                      parse(resStr).rightValue.as[DynamicCfg].rightValue
                    assertConsumerConfig(upd, res)
                  }
              }
            }
        }

      "successfully update an existing dynamic producer client config" in
        withOpenIdConnectServerAndToken(useJwtCreds = false) {
          case (_, _, oidcClient, oidcCfg, token) =>
            withAdminContext(
              useDynamicConfigs = true,
              optOpenIdCfg = Option(oidcCfg)
            ) { case (_, cfg, sessionRef, dynCfgRef, _) =>
              val route =
                Route.seal(
                  adminRoutes(cfg, sessionRef, dynCfgRef, Some(oidcClient))
                )
              val expDynProdCfgs =
                assertPostNProducerConfigs(3, route, Some(token.bearerToken))
              val exp =
                expDynProdCfgs.tail.head.asInstanceOf[ProducerSpecificLimitCfg]

              eventually {
                Get(s"/admin/client/config/producer/${exp.id}") ~>
                  addCredentials(token.bearerToken) ~>
                  route ~>
                  check {
                    status mustBe OK
                    responseEntity.contentType mustBe `application/json`
                    val resStr = responseAs[String]
                    val res =
                      parse(resStr).rightValue.as[DynamicCfg].rightValue
                    assertProducerConfig(exp, res)
                  }
              }

              val upd     = exp.copy(maxConnections = Some(5))
              val updJson = (upd: DynamicCfg).asJson.spaces2

              Put(s"/admin/client/config/producer/${exp.id}", updJson) ~>
                addCredentials(token.bearerToken) ~>
                route ~>
                check {
                  status mustBe OK
                  responseEntity.contentType mustBe `application/json`
                }

              eventually {
                Get(s"/admin/client/config/producer/${exp.id}") ~>
                  addCredentials(token.bearerToken) ~>
                  route ~>
                  check {
                    status mustBe OK
                    responseEntity.contentType mustBe `application/json`
                    val resStr = responseAs[String]
                    val res =
                      parse(resStr).rightValue.as[DynamicCfg].rightValue
                    assertProducerConfig(upd, res)
                  }
              }
            }
        }

      "successfully delete an existing dynamic consumer client config" in
        withOpenIdConnectServerAndToken(useJwtCreds = false) {
          case (_, _, oidcClient, oidcCfg, token) =>
            withAdminContext(
              useDynamicConfigs = true,
              optOpenIdCfg = Option(oidcCfg)
            ) { case (_, cfg, sessionRef, dynCfgRef, _) =>
              val route =
                Route.seal(
                  adminRoutes(cfg, sessionRef, dynCfgRef, Some(oidcClient))
                )
              val expDynConsCfgs =
                assertPostNConsumerConfigs(3, route, Some(token.bearerToken))
              val exp =
                expDynConsCfgs.tail.head.asInstanceOf[ConsumerSpecificLimitCfg]

              eventually {
                Get(s"/admin/client/config/consumer/${exp.id}") ~>
                  addCredentials(token.bearerToken) ~>
                  route ~>
                  check {
                    status mustBe OK
                    responseEntity.contentType mustBe `application/json`
                    val resStr = responseAs[String]
                    val res =
                      parse(resStr).rightValue.as[DynamicCfg].rightValue
                    assertConsumerConfig(exp, res)
                  }
              }

              Delete(s"/admin/client/config/consumer/${exp.id}") ~>
                addCredentials(token.bearerToken) ~>
                route ~>
                check {
                  status mustBe OK
                  responseEntity.contentType mustBe `application/json`
                }

              eventually {
                Get(s"/admin/client/config/consumer/${exp.id}") ~>
                  addCredentials(token.bearerToken) ~>
                  route ~>
                  check {
                    status mustBe NotFound
                    responseEntity.contentType mustBe `application/json`
                  }
              }
            }
        }

      "successfully delete an existing dynamic producer client config" in
        withOpenIdConnectServerAndToken(useJwtCreds = false) {
          case (_, _, oidcClient, oidcCfg, token) =>
            withAdminContext(
              useDynamicConfigs = true,
              optOpenIdCfg = Option(oidcCfg)
            ) { case (_, cfg, sessionRef, dynCfgRef, _) =>
              val route =
                Route.seal(
                  adminRoutes(cfg, sessionRef, dynCfgRef, Some(oidcClient))
                )
              val expDynProdCfgs =
                assertPostNProducerConfigs(3, route, Some(token.bearerToken))
              val exp =
                expDynProdCfgs.tail.head.asInstanceOf[ProducerSpecificLimitCfg]

              eventually {
                Get(s"/admin/client/config/producer/${exp.id}") ~>
                  addCredentials(token.bearerToken) ~>
                  route ~>
                  check {
                    status mustBe OK
                    responseEntity.contentType mustBe `application/json`
                    val resStr = responseAs[String]
                    val res =
                      parse(resStr).rightValue.as[DynamicCfg].rightValue
                    assertProducerConfig(exp, res)
                  }
              }

              Delete(s"/admin/client/config/producer/${exp.id}") ~>
                addCredentials(token.bearerToken) ~>
                route ~>
                check {
                  status mustBe OK
                  responseEntity.contentType mustBe `application/json`
                }

              eventually {
                Get(s"/admin/client/config/producer/${exp.id}") ~>
                  addCredentials(token.bearerToken) ~>
                  route ~>
                  check {
                    status mustBe NotFound
                    responseEntity.contentType mustBe `application/json`
                  }
              }
            }
        }

      "successfully delete all existing dynamic client configs" in
        withOpenIdConnectServerAndToken(useJwtCreds = false) {
          case (_, _, oidcClient, oidcCfg, token) =>
            withAdminContext(
              useDynamicConfigs = true,
              optOpenIdCfg = Option(oidcCfg),
              useFreshStateTopics = true
            ) { case (_, cfg, sessionRef, dynCfgRef, _) =>
              val route =
                Route.seal(
                  adminRoutes(cfg, sessionRef, dynCfgRef, Some(oidcClient))
                )

              val expDynConsCfgs =
                assertPostNConsumerConfigs(4, route, Some(token.bearerToken))
              val expDynProdCfgs =
                assertPostNProducerConfigs(4, route, Some(token.bearerToken))

              eventually {
                Get("/admin/client/config") ~>
                  addCredentials(token.bearerToken) ~>
                  route ~>
                  check {
                    status mustBe OK
                    responseEntity.contentType mustBe `application/json`
                    val resStr = responseAs[String]
                    val json   = parse(resStr).rightValue
                    val cursor = json.hcursor

                    assertAllStaticConfigs(cursor)
                    assertAllDynamicConfigs(
                      expected = expDynConsCfgs,
                      cursor =
                        cursor.downField("consumers").downField("dynamic")
                    )
                    assertAllDynamicConfigs(
                      expected = expDynProdCfgs,
                      cursor =
                        cursor.downField("producers").downField("dynamic")
                    )
                  }
              }

              Delete("/admin/client/config") ~>
                addCredentials(token.bearerToken) ~>
                route ~>
                check {
                  status mustBe OK
                  responseEntity.contentType mustBe `application/json`
                }

              eventually {
                Get("/admin/client/config") ~>
                  addCredentials(token.bearerToken) ~>
                  route ~>
                  check {
                    status mustBe OK
                    responseEntity.contentType mustBe `application/json`
                    val resStr = responseAs[String]
                    val json   = parse(resStr).rightValue
                    val cursor = json.hcursor

                    assertAllStaticConfigs(cursor)
                    assertAllDynamicConfigs(
                      expected = Seq.empty,
                      cursor =
                        cursor.downField("consumers").downField("dynamic")
                    )
                    assertAllDynamicConfigs(
                      expected = Seq.empty,
                      cursor =
                        cursor.downField("producers").downField("dynamic")
                    )
                  }
              }
            }
        }

      "return 404 when looking up non-existing consumer client cfg" in
        withOpenIdConnectServerAndToken(useJwtCreds = false) {
          case (_, _, oidcClient, oidcCfg, token) =>
            withAdminContext(
              useDynamicConfigs = true,
              optOpenIdCfg = Option(oidcCfg)
            ) { case (_, cfg, sessionRef, dynCfgRef, _) =>
              Get("/admin/client/config/consumer/non-existing") ~>
                addCredentials(token.bearerToken) ~>
                Route.seal(
                  adminRoutes(cfg, sessionRef, dynCfgRef, Some(oidcClient))
                ) ~>
                check {
                  status mustBe NotFound
                  responseEntity.contentType mustBe `application/json`
                }
            }
        }

      "return 404 when looking up non-existing producer client cfg" in
        withOpenIdConnectServerAndToken(useJwtCreds = false) {
          case (_, _, oidcClient, oidcCfg, token) =>
            withAdminContext(
              useDynamicConfigs = true,
              optOpenIdCfg = Option(oidcCfg)
            ) { case (_, cfg, sessionRef, dynCfgRef, _) =>
              Get("/admin/client/config/producer/non-existing") ~>
                addCredentials(token.bearerToken) ~>
                Route.seal(
                  adminRoutes(cfg, sessionRef, dynCfgRef, Some(oidcClient))
                ) ~>
                check {
                  status mustBe NotFound
                  responseEntity.contentType mustBe `application/json`
                }
            }
        }

      "return 400 when trying to add a new dynamic consumer client config" +
        " using invalid JSON" in
        withOpenIdConnectServerAndToken(useJwtCreds = false) {
          case (_, _, oidcClient, oidcCfg, token) =>
            withAdminContext(
              useDynamicConfigs = true,
              optOpenIdCfg = Option(oidcCfg)
            ) { case (_, cfg, sessionRef, dynCfgRef, _) =>
              val json =
                """{
                    |  "max-connections":2,
                    |  "messages-per-second":10,
                    |  "grop-id":"my-consumer"
                    |}""".stripMargin

              val entity = HttpEntity(ContentTypes.`application/json`, json)

              Post("/admin/client/config/consumer", entity) ~>
                addCredentials(token.bearerToken) ~>
                Route.seal(
                  adminRoutes(cfg, sessionRef, dynCfgRef, Some(oidcClient))
                ) ~>
                check {
                  status mustBe BadRequest
                  responseEntity.contentType mustBe `application/json`
                  responseAs[String] mustBe expectedInvalidJson()
                }
            }
        }

      "return 400 when trying to add a new dynamic producer client config" +
        " using invalid JSON" in
        withOpenIdConnectServerAndToken(useJwtCreds = false) {
          case (_, _, oidcClient, oidcCfg, token) =>
            withAdminContext(
              useDynamicConfigs = true,
              optOpenIdCfg = Option(oidcCfg)
            ) { case (_, cfg, sessionRef, dynCfgRef, _) =>
              val json =
                """{
                    |  "max-connections":2,
                    |  "messages-per-second":10,
                    |  "prod-id":"my-producer"
                    |}""".stripMargin

              val entity = HttpEntity(ContentTypes.`application/json`, json)

              Post("/admin/client/config/producer", entity) ~>
                addCredentials(token.bearerToken) ~>
                Route.seal(
                  adminRoutes(cfg, sessionRef, dynCfgRef, Some(oidcClient))
                ) ~>
                check {
                  status mustBe BadRequest
                  responseEntity.contentType mustBe `application/json`
                  responseAs[String] mustBe expectedInvalidJson(false)
                }
            }
        }

      "return 400 when trying to update an existing dynamic consumer client " +
        "config using invalid JSON" in
        withOpenIdConnectServerAndToken(useJwtCreds = false) {
          case (_, _, oidcClient, oidcCfg, token) =>
            withAdminContext(
              useDynamicConfigs = true,
              optOpenIdCfg = Option(oidcCfg)
            ) { case (_, cfg, sessionRef, dynCfgRef, _) =>
              val json =
                """{
                    |  "max-connections":2,
                    |  "messages-per-second":10,
                    |  "grop-id":"my-consumer"
                    |}""".stripMargin

              val entity = HttpEntity(ContentTypes.`application/json`, json)

              Put("/admin/client/config/consumer/my-consumer", entity) ~>
                addCredentials(token.bearerToken) ~>
                Route.seal(
                  adminRoutes(cfg, sessionRef, dynCfgRef, Some(oidcClient))
                ) ~>
                check {
                  status mustBe BadRequest
                  responseEntity.contentType mustBe `application/json`
                  responseAs[String] mustBe expectedInvalidJson()
                }
            }
        }

      "return 400 when trying to update an existing dynamic producer client " +
        "config using invalid JSON" in
        withOpenIdConnectServerAndToken(useJwtCreds = false) {
          case (_, _, oidcClient, oidcCfg, token) =>
            withAdminContext(
              useDynamicConfigs = true,
              optOpenIdCfg = Option(oidcCfg)
            ) { case (_, cfg, sessionRef, dynCfgRef, _) =>
              val json =
                """{
                    |  "max-connections":2,
                    |  "messages-per-second":10,
                    |  "prod-id":"my-producer"
                    |}""".stripMargin

              val entity = HttpEntity(ContentTypes.`application/json`, json)

              Put("/admin/client/config/producer/my-producer", entity) ~>
                addCredentials(token.bearerToken) ~>
                Route.seal(
                  adminRoutes(cfg, sessionRef, dynCfgRef, Some(oidcClient))
                ) ~>
                check {
                  status mustBe BadRequest
                  responseEntity.contentType mustBe `application/json`
                  responseAs[String] mustBe expectedInvalidJson(false)
                }
            }
        }

      "return 404 when updating a non-existing dynamic consumer config" in
        withOpenIdConnectServerAndToken(useJwtCreds = false) {
          case (_, _, oidcClient, oidcCfg, token) =>
            withAdminContext(
              useDynamicConfigs = true,
              optOpenIdCfg = Option(oidcCfg)
            ) { case (_, cfg, sessionRef, dynCfgRef, _) =>
              val route =
                Route.seal(
                  adminRoutes(cfg, sessionRef, dynCfgRef, Some(oidcClient))
                )

              val updJson = createConsumerCfg(
                s"no-such-consumer",
                Some(100),
                Some(100),
                Some(100)
              ).asInstanceOf[DynamicCfg].asJson.spaces2

              Put(s"/admin/client/config/consumer/no-such-config", updJson) ~>
                addCredentials(token.bearerToken) ~>
                route ~> check {
                  status mustBe NotFound
                  responseEntity.contentType mustBe `application/json`
                }
            }
        }

      "return 404 when updating a non-existing dynamic producer config" in
        withOpenIdConnectServerAndToken(useJwtCreds = false) {
          case (_, _, oidcClient, oidcCfg, token) =>
            withAdminContext(
              useDynamicConfigs = true,
              optOpenIdCfg = Option(oidcCfg)
            ) { case (_, cfg, sessionRef, dynCfgRef, _) =>
              val route =
                Route.seal(
                  adminRoutes(cfg, sessionRef, dynCfgRef, Some(oidcClient))
                )

              val updJson = createProducerCfg(
                s"no-such-producer",
                Some(100),
                Some(100)
              ).asInstanceOf[DynamicCfg].asJson.spaces2

              Put(s"/admin/client/config/producer/no-such-config", updJson) ~>
                addCredentials(token.bearerToken) ~>
                route ~> check {
                  status mustBe NotFound
                  responseEntity.contentType mustBe `application/json`
                }
            }
        }

      "return 404 when deleting a non-existing dynamic consumer config" in
        withOpenIdConnectServerAndToken(useJwtCreds = false) {
          case (_, _, oidcClient, oidcCfg, token) =>
            withAdminContext(
              useDynamicConfigs = true,
              optOpenIdCfg = Option(oidcCfg)
            ) { case (_, cfg, sessionRef, dynCfgRef, _) =>
              val route =
                Route.seal(
                  adminRoutes(cfg, sessionRef, dynCfgRef, Some(oidcClient))
                )

              Delete(s"/admin/client/config/consumer/no-such-config") ~>
                addCredentials(token.bearerToken) ~>
                route ~> check {
                  status mustBe NotFound
                  responseEntity.contentType mustBe `application/json`
                }
            }
        }

      "return 404 when deleting a non-existing dynamic producer config" in
        withOpenIdConnectServerAndToken(useJwtCreds = false) {
          case (_, _, oidcClient, oidcCfg, token) =>
            withAdminContext(
              useDynamicConfigs = true,
              optOpenIdCfg = Option(oidcCfg)
            ) { case (_, cfg, sessionRef, dynCfgRef, _) =>
              val route =
                Route.seal(
                  adminRoutes(cfg, sessionRef, dynCfgRef, Some(oidcClient))
                )

              Delete(s"/admin/client/config/producer/no-such-config") ~>
                addCredentials(token.bearerToken) ~>
                route ~> check {
                  status mustBe NotFound
                  responseEntity.contentType mustBe `application/json`
                }
            }
        }

    }
  }
}
