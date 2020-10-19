package net.scalytica.kafka.wsproxy.streams

import java.nio.charset.StandardCharsets

import akka.actor.testkit.typed.scaladsl._
import akka.http.scaladsl.model.ws.{BinaryMessage, TextMessage}
import akka.stream.Materializer
import akka.stream.scaladsl._
import akka.util.ByteString
import net.scalytica.test.WsProxyKafkaSpec
import org.scalatest.{BeforeAndAfterAll, OptionValues}
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.matchers.must.Matchers
import org.scalatest.time.{Minute, Span}
import org.scalatest.wordspec.AnyWordSpec

class ProxyFlowExtrasSpec
    extends AnyWordSpec
    with WsProxyKafkaSpec
    with BeforeAndAfterAll
    with Matchers
    with OptionValues
    with Eventually
    with ScalaFutures {

  import ProxyFlowExtras._

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(1, Minute))

  val config = defaultTypesafeConfig

  val atk = ActorTestKit("proxy-flow-extras-spec", config)

  implicit val as  = atk.system
  implicit val mat = Materializer.matFromSystem

  override def afterAll(): Unit = {
    mat.shutdown()
    as.terminate()
    atk.shutdownTestKit()

    super.afterAll()
  }

  private[this] val theString = "This is a test"

  private[this] val binaryData =
    BinaryMessage(ByteString(theString, StandardCharsets.UTF_8))

  private[this] val textData = TextMessage(theString)

  "ProxyFlowExtras" when {
    "expecting a text message" should {
      "not convert binary messages" in {
        Source
          .single(binaryData)
          .via(wsMessageToStringFlow)
          .runWith(Sink.seq[String])
          .futureValue must have size 0
      }

      "successfully convert a text message" in {
        val result = Source
          .single(textData)
          .via(wsMessageToStringFlow)
          .runWith(Sink.seq[String])
          .futureValue

        result must have size 1
        result.headOption.value mustBe theString
      }
    }

    "expecting a binary message" should {
      "not convert text messages" in {
        Source
          .single(textData)
          .via(wsMessageToByteStringFlow)
          .runWith(Sink.seq[ByteString])
          .futureValue must have size 0
      }
      "successfully convert a binary message" in {
        val result = Source
          .single(binaryData)
          .via(wsMessageToByteStringFlow)
          .runWith(Sink.seq[ByteString])
          .futureValue

        result must have size 1
        result.headOption.value mustBe ByteString(theString)
      }
    }
  }

}
