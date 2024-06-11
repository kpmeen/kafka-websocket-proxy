package net.scalytica.kafka.wsproxy.web.admin

import org.apache.pekko.http.scaladsl.model.ContentTypes._
import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.model.StatusCodes._
import org.apache.pekko.http.scaladsl.server._
import org.apache.pekko.http.scaladsl.testkit.RouteTestTimeout
import io.circe.parser._
import io.circe.syntax._
import net.scalytica.kafka.wsproxy.codecs.BasicSerdes.StringSerializer
import net.scalytica.kafka.wsproxy.codecs.Decoders.{
  brokerInfoDecoder,
  consumerGroupDecoder,
  dynamicCfgDecoder,
  partOffMetadataDecoder
}
import net.scalytica.kafka.wsproxy.codecs.Encoders._
import net.scalytica.kafka.wsproxy.config.Configuration.{
  ConsumerSpecificLimitCfg,
  DynamicCfg,
  ProducerSpecificLimitCfg
}
import net.scalytica.kafka.wsproxy.models.{
  BrokerInfo,
  ConsumerGroup,
  PartitionOffsetMetadata,
  TopicName
}
import net.scalytica.test.SharedAttributes.basicHttpCreds
import net.scalytica.test.TestDataGenerators.{
  createConsumerCfg,
  createPartitionOffsetMetadataList,
  createProducerCfg
}
import org.apache.kafka.clients.producer.ProducerRecord
// scalastyle:off line.size.limit
import org.apache.kafka.coordinator.group.consumer.ConsumerGroup.ConsumerGroupState
// scalastyle:on line.size.limit
import org.scalatest.Inspectors.forAll
import org.scalatest.time.{Seconds, Span}

import scala.concurrent.duration._

// scalastyle:off magic.number
class AdminRoutesSpec extends BaseAdminRoutesSpec {

  override protected val testTopicPrefix: String = "admin-plain-test-topic"

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(30, Seconds))

  implicit val timeout: RouteTestTimeout = RouteTestTimeout(20 seconds)

  "using the Kafka cluster info routes" when {

    "the proxy is not secured" should {

      "successfully return the result" in withAdminContext() {
        case (kcfg, cfg, sessionRef, dynCfgRef, _) =>
          Get("/admin/kafka/info") ~>
            Route.seal(adminRoutes(cfg, sessionRef, dynCfgRef, None)) ~>
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

      "ignore basic auth header when not enabled" in withAdminContext() {
        case (_, cfg, sessionRef, dynCfgRef, _) =>
          Get("/admin/kafka/info") ~>
            addCredentials(basicHttpCreds) ~>
            Route.seal(adminRoutes(cfg, sessionRef, dynCfgRef, None)) ~>
            check {
              status mustBe OK
              responseEntity.contentType mustBe `application/json`
            }
      }
    }

    "using client config routes and dynamic configs are not enabled" should {

      "only return all static client configs" in
        withAdminContext() { case (_, cfg, sessionRef, dynCfgRef, _) =>
          Get("/admin/client/config") ~>
            Route.seal(adminRoutes(cfg, sessionRef, dynCfgRef, None)) ~>
            check {
              status mustBe OK
              responseEntity.contentType mustBe `application/json`
              val resStr = responseAs[String]
              val json   = parse(resStr).rightValue
              val cursor = json.hcursor

              assertAllStaticConfigs(cursor)
              assertAllDynamicConfigs(
                expected = Seq.empty,
                cursor = cursor.downField("consumers").downField("dynamic")
              )
              assertAllDynamicConfigs(
                expected = Seq.empty,
                cursor = cursor.downField("producers").downField("dynamic")
              )
            }
        }

      "return the static config for a specific consumer when it exists" in
        withAdminContext() { case (_, cfg, sessionRef, dynCfgRef, _) =>
          Get("/admin/client/config/consumer/dummy") ~>
            Route.seal(adminRoutes(cfg, sessionRef, dynCfgRef, None)) ~>
            check {
              status mustBe OK
              responseEntity.contentType mustBe `application/json`
              val resStr = responseAs[String]
              val res    = parse(resStr).rightValue.as[DynamicCfg].rightValue
              assertConsumerConfig(ExpStaticCons2, res)
            }
        }

      "return the static config for a specific producer when it exists" in
        withAdminContext() { case (_, cfg, sessionRef, dynCfgRef, _) =>
          Get("/admin/client/config/producer/limit-test-producer-2") ~>
            Route.seal(adminRoutes(cfg, sessionRef, dynCfgRef, None)) ~>
            check {
              status mustBe OK
              responseEntity.contentType mustBe `application/json`
              val resStr = responseAs[String]
              val res    = parse(resStr).rightValue.as[DynamicCfg].rightValue
              assertProducerConfig(ExpStaticProd3, res)
            }
        }

      "return 405 when trying to add new dynamic configs" in
        withAdminContext() { case (_, cfg, sessionRef, dynCfgRef, _) =>
          val cconf = createConsumerCfg("my-consumer", Some(10), Some(2))
          val json  = (cconf: DynamicCfg).asJson.spaces2

          val entity = HttpEntity(ContentTypes.`application/json`, json)

          Post("/admin/client/config/consumer", entity) ~>
            Route.seal(adminRoutes(cfg, sessionRef, dynCfgRef, None)) ~>
            check {
              status mustBe MethodNotAllowed
              responseEntity.contentType mustBe `application/json`
            }
        }

      "return 405 when trying to remove a dynamic config" in
        withAdminContext() { case (_, cfg, sessionRef, dynCfgRef, _) =>
          Delete("/admin/client/config/consumer/my-consumer") ~>
            Route.seal(adminRoutes(cfg, sessionRef, dynCfgRef, None)) ~>
            check {
              status mustBe MethodNotAllowed
              responseEntity.contentType mustBe `application/json`
            }
        }

      "return 405 when trying to remove all dynamic configs" in
        withAdminContext() { case (_, cfg, sessionRef, dynCfgRef, _) =>
          Delete("/admin/client/config") ~>
            Route.seal(adminRoutes(cfg, sessionRef, dynCfgRef, None)) ~>
            check {
              status mustBe MethodNotAllowed
              responseEntity.contentType mustBe `application/json`
            }
        }
    }

    "using client config routes and dynamic configs are enabled" should {

      "successfully add a new dynamic consumer client config" in
        withAdminContext(useDynamicConfigs = true) {
          case (_, cfg, sessionRef, dynCfgRef, _) =>
            val route =
              Route.seal(adminRoutes(cfg, sessionRef, dynCfgRef, None))
            val cconf = createConsumerCfg("my-consumer", Some(10), Some(2))

            assertPostConsumerConfig(cconf, route)

            eventually {
              Get("/admin/client/config/consumer/my-consumer") ~> route ~>
                check {
                  status mustBe OK
                  responseEntity.contentType mustBe `application/json`
                  val resStr = responseAs[String]
                  val res = parse(resStr).rightValue.as[DynamicCfg].rightValue
                  assertConsumerConfig(cconf, res)
                }
            }
        }

      "successfully add a new dynamic producer client config" in
        withAdminContext(useDynamicConfigs = true) {
          case (_, cfg, sessionRef, dynCfgRef, _) =>
            val route =
              Route.seal(adminRoutes(cfg, sessionRef, dynCfgRef, None))
            val pconf = createProducerCfg("my-producer-1", Some(10), Some(2))

            assertPostProducerConfig(pconf, route)

            eventually {
              Get("/admin/client/config/producer/my-producer-1") ~> route ~>
                check {
                  status mustBe OK
                  responseEntity.contentType mustBe `application/json`
                  val resStr = responseAs[String]
                  val res = parse(resStr).rightValue.as[DynamicCfg].rightValue
                  assertProducerConfig(pconf, res)
                }
            }

        }

      "successfully return all static and dynamic client configs" in
        withAdminContext(useDynamicConfigs = true, useFreshStateTopics = true) {
          case (_, cfg, sessionRef, dynCfgRef, _) =>
            val route =
              Route.seal(adminRoutes(cfg, sessionRef, dynCfgRef, None))

            val expDynConsCfgs = assertPostNConsumerConfigs(4, route)
            val expDynProdCfgs = assertPostNProducerConfigs(4, route)

            eventually {
              Get("/admin/client/config") ~> route ~> check {
                status mustBe OK
                responseEntity.contentType mustBe `application/json`
                val resStr = responseAs[String]
                val json   = parse(resStr).rightValue
                val cursor = json.hcursor

                assertAllStaticConfigs(cursor)
                assertAllDynamicConfigs(
                  expected = expDynConsCfgs,
                  cursor = cursor.downField("consumers").downField("dynamic")
                )
                assertAllDynamicConfigs(
                  expected = expDynProdCfgs,
                  cursor = cursor.downField("producers").downField("dynamic")
                )
              }
            }
        }

      "successfully return a specific dynamic consumer client config" in
        withAdminContext(useDynamicConfigs = true) {
          case (_, cfg, sessionRef, dynCfgRef, _) =>
            val route =
              Route.seal(adminRoutes(cfg, sessionRef, dynCfgRef, None))
            val expDynConsCfgs = assertPostNConsumerConfigs(3, route)
            val exp =
              expDynConsCfgs.tail.head.asInstanceOf[ConsumerSpecificLimitCfg]

            eventually {
              Get(
                s"/admin/client/config/consumer/${exp.id}"
              ) ~> route ~> check {
                status mustBe OK
                responseEntity.contentType mustBe `application/json`
                val resStr = responseAs[String]
                val res    = parse(resStr).rightValue.as[DynamicCfg].rightValue
                assertConsumerConfig(exp, res)
              }
            }
        }

      "successfully return a specific static consumer client config" in
        withAdminContext(useDynamicConfigs = true) {
          case (_, cfg, sessionRef, dynCfgRef, _) =>
            Get("/admin/client/config/consumer/dummy") ~>
              Route.seal(adminRoutes(cfg, sessionRef, dynCfgRef, None)) ~>
              check {
                status mustBe OK
                responseEntity.contentType mustBe `application/json`
                val resStr = responseAs[String]
                val res    = parse(resStr).rightValue.as[DynamicCfg].rightValue
                assertConsumerConfig(ExpStaticCons2, res)
              }
        }

      "successfully return a specific dynamic producer client config" in
        withAdminContext(useDynamicConfigs = true) {
          case (_, cfg, sessionRef, dynCfgRef, _) =>
            val route =
              Route.seal(adminRoutes(cfg, sessionRef, dynCfgRef, None))
            val expDynProdCfgs = assertPostNProducerConfigs(3, route)
            val exp =
              expDynProdCfgs.tail.head.asInstanceOf[ProducerSpecificLimitCfg]

            eventually {
              Get(
                s"/admin/client/config/producer/${exp.id}"
              ) ~> route ~> check {
                status mustBe OK
                responseEntity.contentType mustBe `application/json`
                val resStr = responseAs[String]
                val res    = parse(resStr).rightValue.as[DynamicCfg].rightValue
                assertProducerConfig(exp, res)
              }
            }
        }

      "successfully return a specific static producer client config" in
        withAdminContext(useDynamicConfigs = true) {
          case (_, cfg, sessionRef, dynCfgRef, _) =>
            Get("/admin/client/config/producer/limit-test-producer-2") ~>
              Route.seal(adminRoutes(cfg, sessionRef, dynCfgRef, None)) ~>
              check {
                status mustBe OK
                responseEntity.contentType mustBe `application/json`
                val resStr = responseAs[String]
                val res    = parse(resStr).rightValue.as[DynamicCfg].rightValue
                assertProducerConfig(ExpStaticProd3, res)
              }
        }

      "successfully update an existing dynamic consumer client config" in
        withAdminContext(useDynamicConfigs = true) {
          case (_, cfg, sessionRef, dynCfgRef, _) =>
            val route =
              Route.seal(adminRoutes(cfg, sessionRef, dynCfgRef, None))
            val expDynConsCfgs = assertPostNConsumerConfigs(3, route)
            val exp =
              expDynConsCfgs.tail.head.asInstanceOf[ConsumerSpecificLimitCfg]

            eventually {
              Get(s"/admin/client/config/consumer/${exp.id}") ~>
                route ~> check {
                  status mustBe OK
                  responseEntity.contentType mustBe `application/json`
                  val resStr = responseAs[String]
                  val res = parse(resStr).rightValue.as[DynamicCfg].rightValue
                  assertConsumerConfig(exp, res)
                }
            }

            val upd     = exp.copy(maxConnections = Some(5))
            val updJson = (upd: DynamicCfg).asJson.spaces2

            Put(s"/admin/client/config/consumer/${exp.id}", updJson) ~>
              route ~>
              check {
                status mustBe OK
                responseEntity.contentType mustBe `application/json`
              }

            eventually {
              Get(s"/admin/client/config/consumer/${exp.id}") ~>
                route ~> check {
                  status mustBe OK
                  responseEntity.contentType mustBe `application/json`
                  val resStr = responseAs[String]
                  val res = parse(resStr).rightValue.as[DynamicCfg].rightValue
                  assertConsumerConfig(upd, res)
                }
            }
        }

      "successfully update an existing dynamic producer client config" in
        withAdminContext(useDynamicConfigs = true) {
          case (_, cfg, sessionRef, dynCfgRef, _) =>
            val route =
              Route.seal(adminRoutes(cfg, sessionRef, dynCfgRef, None))
            val expDynProdCfgs = assertPostNProducerConfigs(3, route)
            val exp =
              expDynProdCfgs.tail.head.asInstanceOf[ProducerSpecificLimitCfg]

            eventually {
              Get(s"/admin/client/config/producer/${exp.id}") ~>
                route ~> check {
                  status mustBe OK
                  responseEntity.contentType mustBe `application/json`
                  val resStr = responseAs[String]
                  val res = parse(resStr).rightValue.as[DynamicCfg].rightValue
                  assertProducerConfig(exp, res)
                }
            }

            val upd     = exp.copy(maxConnections = Some(5))
            val updJson = (upd: DynamicCfg).asJson.spaces2

            Put(s"/admin/client/config/producer/${exp.id}", updJson) ~>
              route ~>
              check {
                status mustBe OK
                responseEntity.contentType mustBe `application/json`
              }

            eventually {
              Get(s"/admin/client/config/producer/${exp.id}") ~>
                route ~> check {
                  status mustBe OK
                  responseEntity.contentType mustBe `application/json`
                  val resStr = responseAs[String]
                  val res = parse(resStr).rightValue.as[DynamicCfg].rightValue
                  assertProducerConfig(upd, res)
                }
            }
        }

      "successfully delete an existing dynamic consumer client config" in
        withAdminContext(useDynamicConfigs = true) {
          case (_, cfg, sessionRef, dynCfgRef, _) =>
            val route =
              Route.seal(adminRoutes(cfg, sessionRef, dynCfgRef, None))
            val expDynConsCfgs = assertPostNConsumerConfigs(3, route)
            val exp =
              expDynConsCfgs.tail.head.asInstanceOf[ConsumerSpecificLimitCfg]

            eventually {
              Get(s"/admin/client/config/consumer/${exp.id}") ~>
                route ~> check {
                  status mustBe OK
                  responseEntity.contentType mustBe `application/json`
                  val resStr = responseAs[String]
                  val res = parse(resStr).rightValue.as[DynamicCfg].rightValue
                  assertConsumerConfig(exp, res)
                }
            }

            Delete(s"/admin/client/config/consumer/${exp.id}") ~>
              route ~> check {
                status mustBe OK
                responseEntity.contentType mustBe `application/json`
              }

            eventually {
              Get(s"/admin/client/config/consumer/${exp.id}") ~>
                route ~> check {
                  status mustBe NotFound
                  responseEntity.contentType mustBe `application/json`
                }
            }
        }

      "successfully delete an existing dynamic producer client config" in
        withAdminContext(useDynamicConfigs = true) {
          case (_, cfg, sessionRef, dynCfgRef, _) =>
            val route =
              Route.seal(adminRoutes(cfg, sessionRef, dynCfgRef, None))
            val expDynProdCfgs = assertPostNProducerConfigs(3, route)
            val exp =
              expDynProdCfgs.tail.head.asInstanceOf[ProducerSpecificLimitCfg]

            eventually {
              Get(s"/admin/client/config/producer/${exp.id}") ~>
                route ~> check {
                  status mustBe OK
                  responseEntity.contentType mustBe `application/json`
                  val resStr = responseAs[String]
                  val res = parse(resStr).rightValue.as[DynamicCfg].rightValue
                  assertProducerConfig(exp, res)
                }
            }

            Delete(s"/admin/client/config/producer/${exp.id}") ~>
              route ~> check {
                status mustBe OK
                responseEntity.contentType mustBe `application/json`
              }

            eventually {
              Get(s"/admin/client/config/producer/${exp.id}") ~>
                route ~> check {
                  status mustBe NotFound
                  responseEntity.contentType mustBe `application/json`
                }
            }
        }

      "successfully delete all existing dynamic client configs" in
        withAdminContext(useDynamicConfigs = true, useFreshStateTopics = true) {
          case (_, cfg, sessionRef, dynCfgRef, _) =>
            val route =
              Route.seal(adminRoutes(cfg, sessionRef, dynCfgRef, None))

            val expDynConsCfgs = assertPostNConsumerConfigs(4, route)
            val expDynProdCfgs = assertPostNProducerConfigs(4, route)

            eventually {
              Get("/admin/client/config") ~> route ~> check {
                status mustBe OK
                responseEntity.contentType mustBe `application/json`
                val resStr = responseAs[String]
                val json   = parse(resStr).rightValue
                val cursor = json.hcursor

                assertAllStaticConfigs(cursor)
                assertAllDynamicConfigs(
                  expected = expDynConsCfgs,
                  cursor = cursor.downField("consumers").downField("dynamic")
                )
                assertAllDynamicConfigs(
                  expected = expDynProdCfgs,
                  cursor = cursor.downField("producers").downField("dynamic")
                )
              }
            }

            Delete("/admin/client/config") ~> route ~> check {
              status mustBe OK
              responseEntity.contentType mustBe `application/json`
            }

            eventually {
              Get("/admin/client/config") ~> route ~> check {
                status mustBe OK
                responseEntity.contentType mustBe `application/json`
                val resStr = responseAs[String]
                val json   = parse(resStr).rightValue
                val cursor = json.hcursor

                assertAllStaticConfigs(cursor)
                assertAllDynamicConfigs(
                  expected = Seq.empty,
                  cursor = cursor.downField("consumers").downField("dynamic")
                )
                assertAllDynamicConfigs(
                  expected = Seq.empty,
                  cursor = cursor.downField("producers").downField("dynamic")
                )
              }
            }
        }

      "return 404 when looking up non-existing consumer client cfg" in
        withAdminContext(useDynamicConfigs = true) {
          case (_, cfg, sessionRef, dynCfgRef, _) =>
            Get("/admin/client/config/consumer/non-existing") ~>
              Route.seal(adminRoutes(cfg, sessionRef, dynCfgRef, None)) ~>
              check {
                status mustBe NotFound
                responseEntity.contentType mustBe `application/json`
              }
        }

      "return 404 when looking up non-existing producer client cfg" in
        withAdminContext(useDynamicConfigs = true) {
          case (_, cfg, sessionRef, dynCfgRef, _) =>
            Get("/admin/client/config/producer/non-existing") ~>
              Route.seal(adminRoutes(cfg, sessionRef, dynCfgRef, None)) ~>
              check {
                status mustBe NotFound
                responseEntity.contentType mustBe `application/json`
              }
        }

      "return 400 when trying to add a new dynamic consumer client config" +
        " using invalid JSON" in
        withAdminContext(useDynamicConfigs = true) {
          case (_, cfg, sessionRef, dynCfgRef, _) =>
            val json =
              """{
                |  "max-connections":2,
                |  "messages-per-second":10,
                |  "grop-id":"my-consumer"
                |}""".stripMargin

            val entity = HttpEntity(ContentTypes.`application/json`, json)

            Post("/admin/client/config/consumer", entity) ~>
              Route.seal(adminRoutes(cfg, sessionRef, dynCfgRef, None)) ~>
              check {
                status mustBe BadRequest
                responseEntity.contentType mustBe `application/json`
                responseAs[String] mustBe expectedInvalidJson()
              }
        }

      "return 400 when trying to add a new dynamic producer client config" +
        " using invalid JSON" in
        withAdminContext(useDynamicConfigs = true) {
          case (_, cfg, sessionRef, dynCfgRef, _) =>
            val json =
              """{
              |  "max-connections":2,
              |  "messages-per-second":10,
              |  "prod-id":"my-producer"
              |}""".stripMargin

            val entity = HttpEntity(ContentTypes.`application/json`, json)

            Post("/admin/client/config/producer", entity) ~>
              Route.seal(adminRoutes(cfg, sessionRef, dynCfgRef, None)) ~>
              check {
                status mustBe BadRequest
                responseEntity.contentType mustBe `application/json`
                responseAs[String] mustBe expectedInvalidJson(false)
              }
        }

      "return 400 when trying to update an existing dynamic consumer client " +
        "config using invalid JSON" in
        withAdminContext(useDynamicConfigs = true) {
          case (_, cfg, sessionRef, dynCfgRef, _) =>
            val json =
              """{
              |  "max-connections":2,
              |  "messages-per-second":10,
              |  "grop-id":"my-consumer"
              |}""".stripMargin

            val entity = HttpEntity(ContentTypes.`application/json`, json)

            Put("/admin/client/config/consumer/my-consumer", entity) ~>
              Route.seal(adminRoutes(cfg, sessionRef, dynCfgRef, None)) ~>
              check {
                status mustBe BadRequest
                responseEntity.contentType mustBe `application/json`
                responseAs[String] mustBe expectedInvalidJson()
              }
        }

      "return 400 when trying to update an existing dynamic producer client " +
        "config using invalid JSON" in
        withAdminContext(useDynamicConfigs = true) {
          case (_, cfg, sessionRef, dynCfgRef, _) =>
            val json =
              """{
              |  "max-connections":2,
              |  "messages-per-second":10,
              |  "prod-id":"my-producer"
              |}""".stripMargin

            val entity = HttpEntity(ContentTypes.`application/json`, json)

            Put("/admin/client/config/producer/my-producer", entity) ~>
              Route.seal(adminRoutes(cfg, sessionRef, dynCfgRef, None)) ~>
              check {
                status mustBe BadRequest
                responseEntity.contentType mustBe `application/json`
                responseAs[String] mustBe expectedInvalidJson(false)
              }
        }

      "return 404 when updating a non-existing dynamic consumer config" in
        withAdminContext(useDynamicConfigs = true) {
          case (_, cfg, sessionRef, dynCfgRef, _) =>
            val route =
              Route.seal(adminRoutes(cfg, sessionRef, dynCfgRef, None))

            val updJson = createConsumerCfg(
              s"no-such-consumer",
              Some(100),
              Some(100),
              Some(100)
            ).asInstanceOf[DynamicCfg].asJson.spaces2

            Put(s"/admin/client/config/consumer/no-such-config", updJson) ~>
              route ~> check {
                status mustBe NotFound
                responseEntity.contentType mustBe `application/json`
              }
        }

      "return 404 when updating a non-existing dynamic producer config" in
        withAdminContext(useDynamicConfigs = true) {
          case (_, cfg, sessionRef, dynCfgRef, _) =>
            val route =
              Route.seal(adminRoutes(cfg, sessionRef, dynCfgRef, None))

            val updJson = createProducerCfg(
              s"no-such-producer",
              Some(100),
              Some(100)
            ).asInstanceOf[DynamicCfg].asJson.spaces2

            Put(s"/admin/client/config/producer/no-such-config", updJson) ~>
              route ~> check {
                status mustBe NotFound
                responseEntity.contentType mustBe `application/json`
              }
        }

      "return 404 when deleting a non-existing dynamic consumer config" in
        withAdminContext(useDynamicConfigs = true) {
          case (_, cfg, sessionRef, dynCfgRef, _) =>
            val route =
              Route.seal(adminRoutes(cfg, sessionRef, dynCfgRef, None))

            Delete(s"/admin/client/config/consumer/no-such-config") ~>
              route ~> check {
                status mustBe NotFound
                responseEntity.contentType mustBe `application/json`
              }
        }

      "return 404 when deleting a non-existing dynamic producer config" in
        withAdminContext(useDynamicConfigs = true) {
          case (_, cfg, sessionRef, dynCfgRef, _) =>
            val route =
              Route.seal(adminRoutes(cfg, sessionRef, dynCfgRef, None))

            Delete(s"/admin/client/config/producer/no-such-config") ~>
              route ~> check {
                status mustBe NotFound
                responseEntity.contentType mustBe `application/json`
              }
        }

    }

    "using the consumer groups routes" should {

      "return an empty list if there are no non-internal consumer groups" in
        withAdminContext() { case (_, cfg, sessionRef, dynCfgRef, _) =>
          val route =
            Route.seal(adminRoutes(cfg, sessionRef, dynCfgRef, None))
          Get(Uri(s"/admin/consumer-group/all")) ~>
            route ~> check {
              status mustBe OK
              responseEntity.contentType mustBe `application/json`
              val resStr = responseAs[String]
              val json   = parse(resStr).rightValue
              val res    = json.as[List[ConsumerGroup]].rightValue
              res mustBe empty
            }
        }

      "return all consumer groups" in
        withAdminContext() { case (_, cfg, sessionRef, dynCfgRef, _) =>
          val route =
            Route.seal(adminRoutes(cfg, sessionRef, dynCfgRef, None))
          // Create a topic to test against
          val topicName = TopicName(s"$testTopicPrefix-cg-1")
          kafkaContext.createTopics(Map(topicName.value -> 1))

          val producer = kafkaProducer[String, String]()
          val _ = producer
            .send(new ProducerRecord(topicName.value, "key1", "value1"))
            .get()

          prepareConsumerGroups(
            topicName = topicName,
            grpNamePrefix = "test-group-a",
            numActiveClients = 2,
            numInactiveClients = 1
          ) { case (_, _) =>
            Get(
              Uri(s"/admin/consumer-group/all").withQuery(
                Uri.Query(Map("includeInternals" -> "true"))
              )
            ) ~>
              route ~> check {
                status mustBe OK
                responseEntity.contentType mustBe `application/json`
                val resStr = responseAs[String]
                val json   = parse(resStr).rightValue
                val res    = json.as[List[ConsumerGroup]].rightValue
                res must not be empty
                forAll(res.map(_.groupId.value)) { gid =>
                  gid must startWith regex """^(?:test-group|ws-proxy)""".r
                }
              }
          }
        }

      "return a list with only the active and non-internal consumer groups" in
        withAdminContext() { case (_, cfg, sessionRef, dynCfgRef, _) =>
          val route =
            Route.seal(adminRoutes(cfg, sessionRef, dynCfgRef, None))
          // Create a topic to test against
          val topicName = TopicName(s"$testTopicPrefix-cg-2")
          kafkaContext.createTopics(Map(topicName.value -> 1))

          val producer = kafkaProducer[String, String]()
          val _ = producer
            .send(new ProducerRecord(topicName.value, "key1", "value1"))
            .get()

          prepareConsumerGroups(
            topicName = topicName,
            grpNamePrefix = "test-group-b",
            numActiveClients = 2,
            numInactiveClients = 1
          ) { case (_, _) =>
            Get(
              Uri(s"/admin/consumer-group/all").withQuery(
                Uri.Query(
                  Map(
                    "activeOnly"       -> "true",
                    "includeInternals" -> "false"
                  )
                )
              )
            ) ~>
              route ~> check {
                status mustBe OK
                responseEntity.contentType mustBe `application/json`
                val resStr = responseAs[String]
                val json   = parse(resStr).rightValue
                val res    = json.as[List[ConsumerGroup]].rightValue
                res must not be empty
                forAll(res) { r =>
                  r.groupId.value must startWith("test-group")
                  r.isActive mustBe true
                }
              }
          }
        }

      "return a description of consumer group" in
        withAdminContext() { case (_, cfg, sessionRef, dynCfgRef, _) =>
          val route =
            Route.seal(adminRoutes(cfg, sessionRef, dynCfgRef, None))
          // Create a topic to test against
          val topicName = TopicName(s"$testTopicPrefix-cg-3")
          kafkaContext.createTopics(Map(topicName.value -> 1))

          val producer = kafkaProducer[String, String]()
          val _ = producer
            .send(new ProducerRecord(topicName.value, "key1", "value1"))
            .get()

          prepareConsumerGroups(
            topicName = topicName,
            grpNamePrefix = "test-group-c"
          ) { case (_, _) =>
            Get(s"/admin/consumer-group/test-group-c-active-1/describe") ~>
              route ~> check {
                status mustBe OK
                responseEntity.contentType mustBe `application/json`
                val resStr = responseAs[String]
                val json   = parse(resStr).rightValue
                val res    = json.as[ConsumerGroup].rightValue
                res.groupId.value mustBe "test-group-c-active-1"
                res.isActive mustBe true
                res.members must have size 1
              }
          }
        }

      "return consumer group with state DEAD when group does not exist" in
        withAdminContext() { case (_, cfg, sessionRef, dynCfgRef, _) =>
          val route =
            Route.seal(adminRoutes(cfg, sessionRef, dynCfgRef, None))
          Get(s"/admin/consumer-group/non-existing-group/describe") ~>
            route ~> check {
              status mustBe OK
              responseEntity.contentType mustBe `application/json`
              val resStr = responseAs[String]
              val json   = parse(resStr).rightValue
              val res    = json.as[ConsumerGroup].rightValue
              res.groupId.value mustBe "non-existing-group"
              res.state mustBe Some(ConsumerGroupState.DEAD.name())
            }
        }

      "return a list of offsets for the given consumer group" in
        withAdminContext() { case (_, cfg, sessionRef, dynCfgRef, _) =>
          val route =
            Route.seal(adminRoutes(cfg, sessionRef, dynCfgRef, None))
          // Create a topic to test against
          val topicName = TopicName(s"$testTopicPrefix-cg-4")
          kafkaContext.createTopics(Map(topicName.value -> 3))

          val producer = kafkaProducer[String, String]()
          (1 to 3).foreach { i =>
            val _ = producer
              .send(
                new ProducerRecord(
                  topicName.value,
                  i - 1,
                  s"key$i",
                  s"value$i"
                )
              )
              .get()
          }

          prepareConsumerGroups(
            topicName = topicName,
            grpNamePrefix = "test-group-d"
          ) { case (_, _) =>
            Get(s"/admin/consumer-group/test-group-d-active-1/offsets") ~>
              route ~> check {
                status mustBe OK
                responseEntity.contentType mustBe `application/json`
                val resStr = responseAs[String]
                val json   = parse(resStr).rightValue
                val res    = json.as[List[PartitionOffsetMetadata]].rightValue
                res must have size 3
                // Sort the result by partition, and verify
                forAll(res.sortBy(_.partition.value).zipWithIndex) {
                  case (pom, i) =>
                    pom.topic mustBe topicName
                    pom.offset.value mustBe 1L
                    pom.partition.value mustBe i
                }
              }
          }
        }

      "return 404 when listing offsets for a non-existing consumer group" in
        withAdminContext() { case (_, cfg, sessionRef, dynCfgRef, _) =>
          val route =
            Route.seal(adminRoutes(cfg, sessionRef, dynCfgRef, None))
          Get(s"/admin/consumer-group/non-existing-group/offsets") ~>
            route ~> check {
              status mustBe NotFound
              responseEntity.contentType mustBe `application/json`
            }
        }

      "return the new offsets after altering consumer group offsets" in
        withAdminContext() { case (_, cfg, sessionRef, dynCfgRef, _) =>
          val route =
            Route.seal(adminRoutes(cfg, sessionRef, dynCfgRef, None))
          // Create a topic to test against
          val topicName = TopicName(s"$testTopicPrefix-cg-5")
          kafkaContext.createTopics(Map(topicName.value -> 3))

          val producer = kafkaProducer[String, String]()
          // Add a few messages to the topic
          (1 to 300).foreach { i =>
            val _ = producer
              .send(
                new ProducerRecord(
                  topicName.value,
                  s"key$i",
                  s"value$i"
                )
              )
              .get()
          }

          val newPartOffsets =
            createPartitionOffsetMetadataList(topicName, 3, 100L)
          val jsonBody = newPartOffsets.asJson.spaces2

          prepareConsumerGroups(
            topicName = topicName,
            grpNamePrefix = "test-group-e"
          ) { case (_, _) => () }

          Put(
            "/admin/consumer-group/test-group-e-active-1/" +
              s"offsets/alter/${topicName.value}",
            jsonBody
          ) ~>
            route ~> check {
              status mustBe OK
              responseEntity.contentType mustBe `application/json`
              val resStr = responseAs[String]
              val json   = parse(resStr).rightValue
              val res    = json.as[List[PartitionOffsetMetadata]].rightValue
              res must have size 3
              // Sort the result by partition, and verify
              forAll(res.sortBy(_.partition.value).zipWithIndex) {
                case (pom, i) =>
                  pom.topic mustBe topicName
                  pom.offset.value mustBe 100L
                  pom.partition.value mustBe i
              }
            }
        }

      "not allow altering consumer group offsets when group is active" in
        withAdminContext() { case (_, cfg, sessionRef, dynCfgRef, _) =>
          val route =
            Route.seal(adminRoutes(cfg, sessionRef, dynCfgRef, None))
          // Create a topic to test against
          val topicName = TopicName(s"$testTopicPrefix-cg-6")
          kafkaContext.createTopics(Map(topicName.value -> 3))

          val producer = kafkaProducer[String, String]()
          // Add a few messages to the topic
          (1 to 10).foreach { i =>
            val _ = producer
              .send(
                new ProducerRecord(
                  topicName.value,
                  s"key$i",
                  s"value$i"
                )
              )
              .get()
          }

          val newPartOffsets =
            createPartitionOffsetMetadataList(topicName, 3, 100L)
          val jsonBody = newPartOffsets.asJson.spaces2

          prepareConsumerGroups(
            topicName = topicName,
            grpNamePrefix = "test-group-f"
          ) { case (_, _) =>
            Put(
              "/admin/consumer-group/test-group-f-active-1/" +
                s"offsets/alter/${topicName.value}",
              jsonBody
            ) ~>
              route ~> check {
                status mustBe PreconditionFailed
                responseEntity.contentType mustBe `application/json`
                val resStr = responseAs[String]
                val msg = parse(resStr).rightValue.hcursor
                  .downField("message")
                  .as[String]
                  .rightValue
                msg mustBe "Unable to modify the consumer group " +
                  "offsets because the group is still considered " +
                  "to be active. Please ensure that all consumer " +
                  "clients are stopped before trying again."

              }
          }
        }

    }
  }
}
