package net.scalytica.test

import org.apache.kafka.common.errors.ProducerFencedException
import org.scalactic.source
import org.scalatest.Assertion
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.util.{Failure, Success, Try}

trait ExtraAssertions { self: AnyWordSpecLike with Matchers =>

  def assertCause[T <: AnyRef](f: => Any)(
      implicit pos: source.Position
  ): Assertion = Try(f) match {
    case Success(_) =>
      fail("Expected a ProducerFencedException")
    case Failure(exception) =>
      exception.getCause mustBe a[ProducerFencedException]
  }

}
