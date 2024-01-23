package net.scalytica.kafka.wsproxy.auth

import org.apache.pekko.actor.Cancellable
import org.apache.pekko.stream.scaladsl.{Keep, Sink, Source}
import org.apache.pekko.stream.testkit.scaladsl.{TestSink, TestSource}
import org.apache.pekko.stream.testkit.{TestPublisher, TestSubscriber}
import org.apache.pekko.util.Timeout
import com.typesafe.config.ConfigValueFactory
import net.scalytica.kafka.wsproxy.config.Configuration.{
  AppCfg,
  OpenIdConnectCfg
}
import net.scalytica.kafka.wsproxy.errors.OpenIdConnectError
import net.scalytica.kafka.wsproxy.models.WsClientId
import net.scalytica.test.{MockOpenIdServer, WsProxyKafkaSpec}
import org.scalatest.BeforeAndAfter
import org.scalatest.RecoverMethods.recoverToExceptionIf
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.must.Matchers
import org.scalatest.time.{Minute, Span}
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.duration._

// scalastyle:off magic.number
class JwtValidationTickerFlowSpec
    extends AnyWordSpec
    with Matchers
    with BeforeAndAfter
    with ScalaFutures
    with WsProxyKafkaSpec
    with MockOpenIdServer {

  override lazy val defaultTypesafeConfig = loadConfig("/application-test.conf")
    .withValue(
      "pekko.http.client.connecting-timeout",
      ConfigValueFactory.fromAnyRef("200ms")
    )
    .withValue(
      // This is a "hack" to ensure the host will not retry HTTP connections
      // on failure in a test scenario.
      "pekko.http.host-connection-pool.max-retries",
      ConfigValueFactory.fromAnyRef(Int.box(0))
    )

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(1, Minute))

  implicit val timeout: Timeout = 200 millis

  val wsClientId = WsClientId("test-client-id")

  private[this] def testAppCfg(oidcCfg: OpenIdConnectCfg): AppCfg = {
    secureAppTestConfig(
      kafkaPort = 9094,
      schemaRegistryPort = None,
      serverOpenIdCfg = Some(oidcCfg)
    )
  }

  private[this] def testSource: Source[Int, TestPublisher.Probe[Int]] =
    TestSource.probe[Int].delay(50 millis)

  private[this] def tickSource: Source[Int, Cancellable] =
    Source.tick(10 millis, 50 millis, 1)

  private[this] def sendAndExpect(
      num: Long,
      pub: TestPublisher.Probe[Int],
      sub: TestSubscriber.Probe[Int]
  ): Unit = {
    sub.request(num)
    (1 to num.intValue()).foreach { i =>
      pub.sendNext(i)
      sub.expectNext(i)
    }
    val _ = pub.sendComplete()
  }

  "The JwtValidationTickerFlow" should {
    "successfully validate a JWT token at a given interval" in
      withOpenIdConnectServerAndClient(
        useJwtCreds = true,
        validationInterval = 500 millis
      ) { case (_, _, client, oidcCfg) =>
        implicit val maybeClient = Option(client)
        implicit val cfg         = testAppCfg(oidcCfg)
        val numMessages          = 50L

        val token = client
          .generateToken(
            clientId = oidClientId,
            clientSecret = oidClientSecret,
            audience = oidAudience,
            grantType = oidGrantTpe
          )
          .futureValue
          .map(_.bearerToken)

        val (pub, sub) = testSource
          .via(JwtValidationTickerFlow.flow[Int](wsClientId, token))
          .toMat(TestSink[Int]())(Keep.both)
          .run()

        sendAndExpect(numMessages, pub, sub)
      }

    "not invalidate a token if the OpenID server is unavailable" in
      withUnavailableOpenIdConnectServerAndToken(
        useJwtCreds = true,
        validationInterval = 500 millis
      ) { case (client, oidcCfg, accessToken) =>
        implicit val maybeClient = Option(client)
        implicit val cfg         = testAppCfg(oidcCfg)
        val token                = Option(accessToken.bearerToken)
        val numMessages          = 100L

        val (pub, sub) = testSource
          .via(JwtValidationTickerFlow.flow[Int](wsClientId, token))
          .toMat(TestSink[Int]())(Keep.both)
          .run()

        sendAndExpect(numMessages, pub, sub)
      }

    "fail when OpenID server has been unavailable the previous n attempts" in
      withUnavailableOpenIdConnectServerAndToken(
        useJwtCreds = true,
        validationInterval = 500 millis,
        errorLimit = 3
      ) { case (client, oidcCfg, accessToken) =>
        implicit val maybeClient = Option(client)
        implicit val cfg         = testAppCfg(oidcCfg)
        val token                = Option(accessToken.bearerToken)

        val expMsg = "OpenID Connect server does not seem to be available."

        val futDone = tickSource
          .via(JwtValidationTickerFlow.flow[Int](wsClientId, token))
          .runWith(Sink.ignore)

        recoverToExceptionIf[OpenIdConnectError](
          futDone
        ).futureValue.message mustBe expMsg
      }

    "invalidate a token that has become invalid" in {
      pending
    }
  }
  // scalastyle:on magic.number
}
