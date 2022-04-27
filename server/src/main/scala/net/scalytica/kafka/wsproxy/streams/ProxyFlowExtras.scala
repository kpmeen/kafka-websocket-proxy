package net.scalytica.kafka.wsproxy.streams

import akka.NotUsed
import akka.http.scaladsl.model.ws.{BinaryMessage, Message, TextMessage}
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Sink}
import akka.util.ByteString
import net.scalytica.kafka.wsproxy.logging.WithProxyLogger

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

trait ProxyFlowExtras { self: WithProxyLogger =>

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

private[streams] object ProxyFlowExtras
    extends ProxyFlowExtras
    with WithProxyLogger
