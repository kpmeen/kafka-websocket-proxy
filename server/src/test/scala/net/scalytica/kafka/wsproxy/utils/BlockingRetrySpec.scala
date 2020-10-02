package net.scalytica.kafka.wsproxy.utils

import net.scalytica.kafka.wsproxy.NiceClassNameExtensions
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.must.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.TimeoutException
import scala.concurrent.duration._
import scala.util.control.NoStackTrace

// scalastyle:off magic.number
class BlockingRetrySpec extends AnyWordSpec with Matchers with ScalaFutures {

  case class RetryTestException()
      extends Exception("retry test exception")
      with NoStackTrace

  implicit override val patienceConfig = PatienceConfig(
    timeout = scaled(Span(10, Seconds)),
    interval = scaled(Span(15, Millis))
  )

  private[this] val completed = "completed"

  // Function for tests that should fail. For example testing retries
  private[this] def failFastFuture = throw RetryTestException()

  // Function that will run for the duration specified in the delay
  private[this] def testOp[T](
      value: T,
      delay: FiniteDuration = 0 seconds
  ): T = {
    Thread.sleep(delay.toMillis)
    value
  }

  // Function that will fail with a TimeoutException
  private[this] def timeoutOp[T](delay: FiniteDuration): T = {
    Thread.sleep(delay.toMillis)
    throw new TimeoutException("Timing out from test")
  }

  s"The ${BlockingRetry.niceClassNameShort}" should {

    "successfully complete a future" in {
      BlockingRetry.retry(1 second, 10 millis, 3)(testOp("completed")) {
        fail("Expected successful Future execution", _)
      } mustBe completed
    }

    "successfully complete a future before retry count reaches limit" in {
      BlockingRetry.retry(4 seconds, 10 millis, 5)(
        testOp("completed", 500 millis)
      ) { err =>
        fail("Expected successful Future execution", err)
      } mustBe completed
    }

    "allow the Future to fail when the timeout expires" in {
      assertThrows[TimeoutException] {
        BlockingRetry.retry(
          timeout = 3 seconds,
          interval = 10 millis,
          numRetries = 3
        )(timeoutOp(2 seconds))(err => throw err)
      }
    }

    "allow the Future to fail when all retries have been used" in {
      assertThrows[RetryTestException] {
        BlockingRetry.retry(
          timeout = 3 seconds,
          interval = 10 millis,
          numRetries = 3
        )(failFastFuture)(throw _)
      }
    }

  }
}
