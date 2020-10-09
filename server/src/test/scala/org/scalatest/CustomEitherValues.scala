package org.scalatest

import org.scalactic.source.Position
import org.scalatest.Resources.{
  eitherLeftValueNotDefined,
  eitherRightValueNotDefined
}
import org.scalatest.exceptions.{StackDepthException, TestFailedException}

/**
 * Implementation of EitherValues that works around the need to use the
 * deprecated .right function on Either values. The ScalaTest community is
 * working on a solution.
 *
 * @see
 *   https://github.com/scalatest/scalatest/issues/972
 */
trait CustomEitherValues {

  implicit def convertEitherToValuableEither[L, R](either: Either[L, R])(
      implicit pos: Position
  ): ValuableEither[L, R] = new ValuableEither(either, pos)

  private[this] def testFailedException(
      messageFun: StackDepthException => String,
      causeMessage: String,
      pos: Position
  ): TestFailedException = {
    new TestFailedException(
      messageFun = sde => Option(messageFun(sde)),
      cause = Some(new NoSuchElementException(causeMessage)),
      pos = pos
    )
  }

  class ValuableEither[L, R](either: Either[L, R], pos: Position) {

    def rightValue: R = either match {
      case Right(r) => r
      case Left(_) =>
        throw testFailedException(
          messageFun = sde => eitherRightValueNotDefined(sde),
          causeMessage = "Either.rightValue on Left",
          pos = pos
        )
    }

    def leftValue: L = either match {
      case Left(l) => l
      case Right(_) =>
        throw testFailedException(
          messageFun = sde => eitherLeftValueNotDefined(sde),
          causeMessage = "Either.leftValue on Right",
          pos = pos
        )
    }
  }

}
