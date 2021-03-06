package net.scalytica.kafka.wsproxy.utils

import com.typesafe.scalalogging.Logger

import scala.annotation.tailrec
import scala.concurrent.blocking
import scala.concurrent.duration._
import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try}

object BlockingRetry {

  implicit val ct = ClassTag(getClass)

  private[this] val logger = Logger(ct)

  private[this] def sleep(duration: FiniteDuration): Unit =
    try {
      blocking(Thread.sleep(duration.toMillis))
    } catch {
      case e: InterruptedException =>
        Thread.currentThread().interrupt()
        throw e
    }

  @tailrec
  private[this] def retryLoop[T](
      remainingRetries: Int,
      interval: FiniteDuration
  )(op: => T)(err: Throwable => T)(implicit deadline: Deadline): T = {
    Try(op) match {
      case Success(res) => res
      case Failure(t) =>
        if (deadline.hasTimeLeft() && remainingRetries > 0) {
          logger.warn(s"Retrying operation in $interval...")
          sleep(interval)
          retryLoop(remainingRetries - 1, interval)(op)(err)
        } else {
          logger.error(
            "Giving up after exhausting retries or reaching" +
              s" timeout of ${deadline.time.toMillis} millis"
          )
          err(t)
        }
    }
  }

  def retry[T](
      timeout: FiniteDuration,
      interval: FiniteDuration,
      numRetries: Int
  )(op: => T)(err: Throwable => T): T =
    retryLoop(numRetries, interval)(op)(err)(timeout.fromNow)

}
