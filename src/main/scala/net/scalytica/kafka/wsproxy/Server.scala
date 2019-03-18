package net.scalytica.kafka.wsproxy

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Source}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.ws.UpgradeToWebSocket
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, Uri}
import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.model.StatusCodes._
import akka.kafka.scaladsl.Consumer
import com.typesafe.scalalogging.Logger
import net.scalytica.kafka.wsproxy.consumer.WsConsumer
import net.scalytica.kafka.wsproxy.producer.WsProducer
import net.scalytica.kafka.wsproxy.serdes.Common._

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.io.StdIn

object Server extends App {

  val logger = Logger(this.getClass)

  implicit val sys: ActorSystem       = ActorSystem()
  implicit val mat: ActorMaterializer = ActorMaterializer()
  implicit val ctx: ExecutionContext  = sys.dispatcher

  val port = 8080

  private[this] def kafkaSink(topic: String) = {
    Flow[Message]
      .mapAsync(1) {
        case tm: TextMessage =>
          tm.toStrict(2 seconds).map(tm => Some(tm.text))
        case _ =>
          Future.successful(None)
      }
      .collect { case Some(tm) => tm }
      .map { str =>
        str.split("->", 2).map(_.trim).toList match {
          case k :: v :: Nil => Some(k -> v)
          case invalid =>
            logger.error(s"Malformed message: $invalid")
            None
        }
      }
      .collect { case Some(kv) => kv }
      .to(WsProducer.kafkaSink[String, String](topic))
  }

  private[this] def kafkaSource(
      topic: String,
      clientId: String
  ): Source[TextMessage.Strict, Consumer.Control] = {
    WsConsumer.kafkaSource[String, String](topic, clientId).map { cr =>
      TextMessage(
        s"topic: ${cr.topic}, partition: ${cr.partition}, " +
          s"key: ${cr.key}, value: ${cr.value}"
      )
    }
  }

  val requestHandler: HttpRequest => HttpResponse = {
    case req @ HttpRequest(GET, Uri.Path("/"), _, _, _) =>
      req.header[UpgradeToWebSocket] match {
        case Some(upgrade) =>
          upgrade.handleMessagesWithSinkSource(
            inSink = kafkaSink("foo"),
            outSource = kafkaSource("foo", "wsproxy")
          )

        case None =>
          HttpResponse(BadRequest, entity = "Not a valid websocket request!")
      }
    case r: HttpRequest =>
      r.discardEntityBytes() // important to drain incoming HTTP Entity stream
      HttpResponse(NotFound, entity = "Unknown resource!")
  }

  val bindingFuture =
    Http()
      .bindAndHandleSync(requestHandler, interface = "localhost", port = port)

  // scalastyle:off
  println(s"Server online at http://localhost:8080/\nPress RETURN to stop...")
  // scalastyle:on
  StdIn.readLine()

  bindingFuture
    .flatMap(_.unbind()) // trigger unbinding from the port
    .onComplete(_ => sys.terminate()) // and shutdown when done
}
