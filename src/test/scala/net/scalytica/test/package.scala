package net.scalytica

import akka.http.scaladsl.model.ws.TextMessage
import akka.http.scaladsl.testkit.WSProbe
import akka.stream.Materializer
import akka.stream.scaladsl.Sink
import io.circe.Decoder
import io.circe.parser.parse
import net.scalytica.kafka.wsproxy.codecs.Decoders._
import net.scalytica.kafka.wsproxy.models.{
  ConsumerKeyValueRecord,
  ConsumerValueRecord,
  WsConsumerRecord,
  WsProducerResult
}
import org.scalatest.{Assertion, MustMatchers}

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success}

package object test {

  implicit class AddFutureAwaitResult[T](future: Future[T]) {

    /** "Safe" Await.result that doesn't throw away half of the stacktrace */
    def awaitResult(atMost: Duration): T = {
      Await.ready(future, atMost)
      future.value.get match {
        case Success(t) ⇒ t
        case Failure(ex) ⇒
          throw new RuntimeException(
            "Trying to await result of failed Future, see the cause for the original problem.",
            ex
          )
      }
    }
  }

  implicit class WsProbeExtensions(probe: WSProbe) extends MustMatchers {

    def expectWsProducerResult(
        expectedTopic: String
    )(implicit mat: Materializer): Assertion = {
      probe.expectMessage() match {
        case tm: TextMessage =>
          val collected = tm.textStream
            .grouped(1000) // scalastyle:ignore
            .runWith(Sink.head)
            .awaitResult(5 seconds)
            .reduce(_ + _)

          parse(collected) match {
            case Left(parseError) => throw parseError
            case Right(js) =>
              js.as[WsProducerResult] match {
                case Left(err) => throw err
                case Right(actual) =>
                  actual.topic mustBe "foobar"
                  actual.offset mustBe >=(0L)
                  actual.partition mustBe >=(0)
              }
          }

        case _ =>
          throw new AssertionError(
            s"""Expected TextMessage but got BinaryMessage"""
          )
      }
    }

    def expectWsConsumerKeyValueResult[K, V](
        expectedTopic: String,
        expectedKey: K,
        expectedValue: V
    )(
        implicit
        mat: Materializer,
        kdec: Decoder[K],
        vdec: Decoder[V]
    ): Assertion = {
      probe.expectMessage() match {
        case tm: TextMessage =>
          val collected = tm.textStream
            .grouped(1000) // scalastyle:ignore
            .runWith(Sink.head)
            .awaitResult(5 seconds)
            .reduce(_ + _)

          parse(collected) match {
            case Left(parseError) => throw parseError
            case Right(js) =>
              js.as[WsConsumerRecord[K, V]] match {
                case Left(err) => throw err
                case Right(actual) =>
                  actual.topic mustBe expectedTopic
                  actual.offset mustBe >=(0L)
                  actual.partition mustBe >=(0)

                  actual match {
                    case kvr: ConsumerKeyValueRecord[K, V] =>
                      kvr.key.value mustBe expectedKey
                      kvr.value.value mustBe expectedValue

                    case vr: ConsumerValueRecord[V] =>
                      vr.value.value mustBe expectedValue
                  }
              }
          }

        case _ =>
          throw new AssertionError(
            s"""Expected TextMessage but got BinaryMessage"""
          )
      }
    }

  }

}
