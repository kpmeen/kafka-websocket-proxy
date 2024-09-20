package net.scalytica.kafka.wsproxy.utils

import java.util.concurrent.TimeUnit

import scala.annotation.tailrec
import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.blocking
import scala.concurrent.duration._
import scala.reflect.ClassTag
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import com.typesafe.scalalogging.Logger

object BlockingRetry {

  implicit val ct: ClassTag[_] = ClassTag(getClass)

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
  )(
      op: => T
  )(
      err: Throwable => T
  )(
      implicit deadline: Deadline
  ): T = {
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

  /**
   * Handle retries for the provided {{{op}}} function.
   *
   * @param timeout
   *   The duration to wait for completion of the {{{op}}}
   * @param interval
   *   The duration to wait between retries
   * @param numRetries
   *   The number of retries to attempt
   * @param op
   *   The function to attempt to execute
   * @param err
   *   Function to handle error situations
   * @tparam T
   *   The return type to expect
   * @return
   *   Returns an instance of {{{T}}}
   */
  def retry[T](
      timeout: FiniteDuration,
      interval: FiniteDuration,
      numRetries: Int
  )(
      op: => T
  )(
      err: Throwable => T
  ): T = {
    require(timeout > interval, "timeout must be greater than interval")
    retryLoop(numRetries, interval)(op)(err)(timeout.fromNow)
  }

  /**
   * Handle retries for the provided {{{op}}} function, which in this case is a
   * {{{Future[T]}}}. The function will block and await a response from the
   * {{{op}}} before returning, or performing a retry.
   *
   * @param timeout
   *   The duration to wait for completion of the {{{op}}}
   * @param interval
   *   The duration to wait between retries
   * @param numRetries
   *   The number of retries to attempt
   * @param op
   *   The Future based function to attempt to execute
   * @param err
   *   A Future function to handle error situations
   * @tparam T
   *   The return type to expect
   * @return
   *   Returns an instance of {{{T}}}
   */
  def retryAwaitFuture[T](
      timeout: FiniteDuration,
      interval: FiniteDuration,
      numRetries: Int
  )(
      op: FiniteDuration => Future[T]
  )(
      err: Throwable => Future[T]
  ): T = {
    require(timeout > interval, "timeout must be greater than interval")
    val attemptTimeoutMillis =
      ((timeout - (interval * numRetries.toLong)) / numRetries.toLong).toMillis

    val attemptTimeout = FiniteDuration(
      length = scala.math.abs(attemptTimeoutMillis),
      unit = TimeUnit.MILLISECONDS
    )
    retryLoop(
      remainingRetries = numRetries,
      interval = interval
    )(
      op = Await.result(op(attemptTimeout), attemptTimeout)
    )(t => Await.result(err(t), 3 seconds))(
      deadline = timeout.fromNow
    )
  }
}
