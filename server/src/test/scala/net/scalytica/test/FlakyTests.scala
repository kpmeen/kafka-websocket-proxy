package net.scalytica.test

import org.scalatest._

trait FlakyTests extends Retries { self: TestSuite =>

  val maxRetries = 5 // scalastyle:ignore

  override def withFixture(test: NoArgTest) = {
    if (isRetryable(test)) withRetryableFixture(test, maxRetries)
    else test()
  }

  def withRetryableFixture(test: NoArgTest, retries: Int): Outcome = {
    val outcome = test()
    outcome match {
      case Failed(_) | Canceled(_) =>
        if (maxRetries == 1) test()
        else withRetryableFixture(test, retries - 1)

      case other =>
        other
    }
  }
}
