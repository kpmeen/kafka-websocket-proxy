package net.scalytica.kafka.wsproxy.web.admin

import io.circe.{ACursor, HCursor, Json}
import io.circe.syntax._
import net.scalytica.kafka.wsproxy.config.Configuration.{
  ConsumerSpecificLimitCfg,
  DynamicCfg,
  ProducerSpecificLimitCfg
}
import net.scalytica.kafka.wsproxy.codecs.Encoders._
import net.scalytica.kafka.wsproxy.codecs.Decoders._
import net.scalytica.test.TestDataGenerators.{
  createConsumerCfg,
  createProducerCfg
}
import net.scalytica.test.{
  TestAdminRoutes,
  WsProxySpec,
  WsReusableProxyKafkaFixture
}
import org.apache.pekko.http.scaladsl.model.ContentTypes._
import org.apache.pekko.http.scaladsl.model.StatusCodes._
import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.model.headers.HttpCredentials
import org.apache.pekko.http.scaladsl.server._
import org.scalatest.Inspectors.forAll
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.matchers.must.Matchers
import org.scalatest.{Assertion, CustomEitherValues, OptionValues}
import org.scalatest.wordspec.AnyWordSpecLike

// scalastyle:off magic.number
trait BaseAdminRoutesSpec
    extends AnyWordSpecLike
    with Matchers
    with CustomEitherValues
    with OptionValues
    with ScalaFutures
    with Eventually
    with TestAdminRoutes
    with WsProxySpec
    with WsReusableProxyKafkaFixture {

  val ExpStaticCons1: ConsumerSpecificLimitCfg = createConsumerCfg(
    id = "__DEFAULT__",
    mps = Some(0),
    mc = Some(0),
    bs = Some(0)
  )

  val ExpStaticCons2: ConsumerSpecificLimitCfg =
    createConsumerCfg(id = "dummy", mps = Some(10), mc = Some(2))

  val ExpStaticProd1: ProducerSpecificLimitCfg =
    createProducerCfg(id = "__DEFAULT__", mps = Some(0), mc = Some(0))

  val ExpStaticProd2: ProducerSpecificLimitCfg = createProducerCfg(
    id = "limit-test-producer-1",
    mps = Some(10),
    mc = Some(1)
  )

  val ExpStaticProd3: ProducerSpecificLimitCfg = createProducerCfg(
    id = "limit-test-producer-2",
    mps = Some(10),
    mc = Some(2)
  )

  def expectedInvalidJson(isConsumer: Boolean = true): String = {
    val t   = "Invalid JSON for %s config."
    val msg = if (isConsumer) t.format("consumer") else t.format("producer")
    Json.obj("message" -> Json.fromString(msg)).spaces2
  }

  def assertAllStaticConfigs(cursor: HCursor): Assertion = {
    val staticConsumers =
      cursor.downField("consumers").downField("static")
    val staticProducers =
      cursor.downField("producers").downField("static")

    val sc1 = staticConsumers.downN(0).as[DynamicCfg].rightValue
    val sc2 = staticConsumers.downN(1).as[DynamicCfg].rightValue
    sc1 mustBe ExpStaticCons1
    sc2 mustBe ExpStaticCons2

    val sp1 = staticProducers.downN(0).as[DynamicCfg].rightValue
    val sp2 = staticProducers.downN(1).as[DynamicCfg].rightValue
    val sp3 = staticProducers.downN(2).as[DynamicCfg].rightValue
    sp1 mustBe ExpStaticProd1
    sp2 mustBe ExpStaticProd2
    sp3 mustBe ExpStaticProd3
  }

  def assertAllDynamicConfigs(
      expected: Seq[DynamicCfg],
      cursor: ACursor
  ): Assertion = {
    val resCfgs = cursor.values.value.map(_.as[DynamicCfg].rightValue)
    resCfgs must have size expected.size.toLong
    if (expected.isEmpty) resCfgs mustBe empty
    else resCfgs must contain allElementsOf expected
  }

  def assertConsumerConfig(
      expected: ConsumerSpecificLimitCfg,
      actual: DynamicCfg
  ): Assertion = {
    actual match {
      case c: ConsumerSpecificLimitCfg =>
        c.groupId mustBe expected.groupId
        c.batchSize mustBe expected.batchSize
        c.messagesPerSecond mustBe expected.messagesPerSecond
        c.maxConnections mustBe expected.maxConnections

      case _ => fail("Got the wrong config type")
    }
  }

  def assertProducerConfig(
      expected: ProducerSpecificLimitCfg,
      actual: DynamicCfg
  ): Assertion = {
    actual match {
      case p: ProducerSpecificLimitCfg =>
        p.producerId mustBe expected.producerId
        p.messagesPerSecond mustBe expected.messagesPerSecond
        p.maxConnections mustBe expected.maxConnections

      case _ => fail("Got the wrong config type")
    }
  }

  def routeWithCredentials(
      maybeCredentials: Option[HttpCredentials] = None
  ): RequestTransformer = { req: HttpRequest =>
    maybeCredentials.map(c => req ~> addCredentials(c)).getOrElse(req)
  }

  def postConfig(
      uri: String,
      conf: DynamicCfg,
      route: Route,
      credentials: Option[HttpCredentials]
  ): Assertion = {
    val json   = (conf: DynamicCfg).asJson.spaces2
    val entity = HttpEntity(ContentTypes.`application/json`, json)

    Post(uri, entity) ~>
      routeWithCredentials(credentials) ~>
      route ~>
      check {
        status mustBe OK
        responseEntity.contentType mustBe `application/json`
      }
  }

  def assertPostConsumerConfig(
      conf: DynamicCfg,
      route: Route,
      credentials: Option[HttpCredentials] = None
  ): Assertion = {
    postConfig("/admin/client/config/consumer", conf, route, credentials)
  }

  def assertPostProducerConfig(
      conf: DynamicCfg,
      route: Route,
      credentials: Option[HttpCredentials] = None
  ): Assertion = {
    postConfig("/admin/client/config/producer", conf, route, credentials)
  }

  def assertPostNConsumerConfigs(
      num: Int,
      route: Route,
      credentials: Option[HttpCredentials] = None
  ): Seq[DynamicCfg] = {
    val cfgs = (1 to num).map { i =>
      createConsumerCfg(s"test-consumer-$i", Some(num * 100), Some(num))
    }
    forAll(cfgs)(c => assertPostConsumerConfig(c, route, credentials))
    cfgs
  }

  def assertPostNProducerConfigs(
      num: Int,
      route: Route,
      credentials: Option[HttpCredentials] = None
  ): Seq[DynamicCfg] = {
    val cfgs = (1 to 10).map { i =>
      createProducerCfg(s"test-producer-$i", Some(num * 100), Some(num))
    }
    forAll(cfgs)(c => assertPostProducerConfig(c, route, credentials))
    cfgs
  }

}
