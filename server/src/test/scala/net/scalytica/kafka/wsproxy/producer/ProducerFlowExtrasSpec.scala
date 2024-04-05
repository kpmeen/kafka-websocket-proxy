package net.scalytica.kafka.wsproxy.producer

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.http.scaladsl.model.ws.{Message, TextMessage}
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl._
import org.apache.pekko.stream.testkit.scaladsl.{TestSink, TestSource}
import net.scalytica.kafka.wsproxy.models.WsProducerId
import net.scalytica.test.SharedAttributes.defaultTypesafeConfig
import net.scalytica.test.{TestDataGenerators, WsProxySpec}
import org.apache.pekko.actor.typed.ActorSystem
import org.scalatest.Inspectors.forAll
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.matchers.must.Matchers
import org.scalatest.time.{Minute, Span}
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{BeforeAndAfterAll, OptionValues}

import scala.concurrent.duration._

// scalastyle:off magic.number
class ProducerFlowExtrasSpec
    extends AnyWordSpec
    with WsProxySpec
    with BeforeAndAfterAll
    with Matchers
    with OptionValues
    with Eventually
    with ScalaFutures {

  override protected val testTopicPrefix: String =
    "producerflow-extras-test-topic"

  import ProducerFlowExtras._

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(1, Minute))

  val atk: ActorTestKit =
    ActorTestKit("producer-flow-extras-spec", defaultTypesafeConfig)

  implicit val as: ActorSystem[Nothing] = atk.system
  implicit val mat: Materializer        = Materializer.matFromSystem

  override def afterAll(): Unit = {
    mat.shutdown()
    as.terminate()
    atk.shutdownTestKit()

    super.afterAll()
  }

  private[this] val testData = TestDataGenerators.createJsonKeyValue(num = 5)

  private[this] val textMessages = testData.map(TextMessage.apply)

  private[this] def streamToTest(msgPerSec: Int) =
    TestSource
      .probe[Message]
      .via(
        rateLimitFlow(
          producerId = WsProducerId("test-client"),
          defaultMessagesPerSecond = msgPerSec,
          clientLimits = Seq.empty
        )
      )
      .toMat(TestSink.probe[Message])(Keep.both)

  "ProducerFlowExtras" when {

    "using the rate limiter flow" should {
      "emit the correct number of messages at the defined default interval" in {
        // Using a slightly smaller throttle delay to account for time used
        // between each message verification
        val delayDuration = 900 millis
        val (in, out)     = streamToTest(1).run()

        out.request(10)
        textMessages.foreach(in.sendNext)
        in.sendComplete()
        // First message has no delay
        out.expectNext(textMessages.head)
        forAll(textMessages.tail) { msg =>
          out.expectNoMessage(delayDuration)
          out.expectNext(msg)
        }
        out.expectComplete()
      }

      "not let messages through faster than the rate limit" in {
        val (in, out) = streamToTest(1).run()

        out.request(10)
        textMessages.foreach(in.sendNext)
        in.sendComplete()
        // First message has no delay
        out.expectNext(textMessages.head)
        val err = intercept[AssertionError] {
          out.expectNext(10 millis, textMessages.tail.head)
        }
        err.getMessage must startWith("assertion failed: timeout")
      }

      "not apply rate limits when the messages per second argument is 0" in {
        val (in, out) = streamToTest(0).run()

        out.request(textMessages.size.toLong)
        textMessages.foreach(in.sendNext)
        in.sendComplete()
        forAll(textMessages) { msg =>
          // Allow for 10 millisecond timeout to receive the message
          out.expectNext(10 millis, msg)
        }
        out.expectComplete()
      }
    }

  }
}
