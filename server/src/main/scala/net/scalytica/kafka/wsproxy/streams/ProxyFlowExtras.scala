package net.scalytica.kafka.wsproxy.streams

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import net.scalytica.kafka.wsproxy.logging.WithProxyLogger

import org.apache.pekko.NotUsed
import org.apache.pekko.http.scaladsl.model.ws.BinaryMessage
import org.apache.pekko.http.scaladsl.model.ws.Message
import org.apache.pekko.http.scaladsl.model.ws.TextMessage
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Flow
import org.apache.pekko.stream.scaladsl.Sink
import org.apache.pekko.util.ByteString

trait ProxyFlowExtras { self: WithProxyLogger =>

  /**
   * Helper function to transform a [[TextMessage]] to a String.
   *
   * @param mat
   *   The implicit [[Materializer]] to use
   * @param ec
   *   The implicit [[ExecutionContext]] to use
   * @return
   *   A [[Flow]] with [[Message]] input and [[String]] output
   */
  def wsMessageToStringFlow(
      implicit mat: Materializer,
      ec: ExecutionContext
  ): Flow[Message, String, NotUsed] =
    Flow[Message]
      .mapConcat {
        case tm: TextMessage   => TextMessage(tm.textStream) :: Nil
        case bm: BinaryMessage => bm.dataStream.runWith(Sink.ignore); Nil
      }
      .mapAsync(1)(_.toStrict(5 seconds).map(_.text))

  /**
   * Helper function to transform a [[BinaryMessage]] to a [[ByteString]].
   *
   * @param mat
   *   The implicit [[Materializer]] to use
   * @param ec
   *   The implicit [[ExecutionContext]] to use
   * @return
   *   A [[Flow]] with [[Message]] input and [[ByteString]] output
   */
  def wsMessageToByteStringFlow(
      implicit mat: Materializer,
      ec: ExecutionContext
  ): Flow[Message, ByteString, NotUsed] = Flow[Message]
    .log("wsMessageToByteStringFlow", _ => "Concatenating incoming bytes...")
    .mapConcat {
      case tm: TextMessage =>
        log.trace("Received TextMessage through socket")
        tm.textStream.runWith(Sink.ignore); Nil

      case bm: BinaryMessage =>
        log.trace("Received BinaryMessage through socket")
        BinaryMessage(bm.dataStream) :: Nil
    }
    .log("wsMessageToByteStringFlow", m => s"Aggregated message: $m")
    .mapAsync(1)(_.toStrict(5 seconds).map(_.data))

}
