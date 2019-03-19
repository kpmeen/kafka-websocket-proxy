package net.scalytica.kafka.wsproxy

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.kafka.scaladsl.Consumer
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Source}
import com.typesafe.scalalogging.Logger
import net.scalytica.kafka.wsproxy.consumer.WsConsumer
import net.scalytica.kafka.wsproxy.producer.WsProducer
import net.scalytica.kafka.wsproxy.serdes.Common._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
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

  implicit def errorHandler: ExceptionHandler = ExceptionHandler {
    case t: Throwable =>
      extractUri { uri =>
        logger.warn(s"Request to $uri could not be handled normally", t)
        complete(HttpResponse(InternalServerError, entity = t.getMessage))
      }
  }

  // Handler of dealing with rejections for WebSockets.
  val wsRejectionHandler = RejectionHandler
    .newBuilder()
    .handleNotFound {
      complete((NotFound, "This is not the resource you are looking for."))
    }
    .handle {
      case ValidationRejection(msg, _) =>
        complete((BadRequest, msg))

      case usp: UnsupportedWebSocketSubprotocolRejection =>
        complete((BadRequest, usp.supportedProtocol))

      case ExpectedWebSocketRequestRejection =>
        complete((BadRequest, "Not a valid websocket request!"))
    }
    .result()

  // The main request handler for incoming requests.
  val requestHandler = Route.seal(
    (path("/") | parameter('topic, 'key_type ?, 'value_type ?)) {
      case (topic, maybeKeyType, maybeValueType) =>
        get {
          handleRejections(wsRejectionHandler) {
            extractUpgradeToWebSocket { upgrade =>
              complete {
                upgrade.handleMessagesWithSinkSource(
                  inSink = kafkaSink(topic),
                  outSource = kafkaSource(topic, "wsproxy")
                )
              }
            }
          }
        }
    }
  )

  val bindingFuture =
    Http().bindAndHandle(requestHandler, interface = "localhost", port = port)

  // scalastyle:off
  println(
    """Server online at http://localhost:8080/
      |Press RETURN to stop..."""
  )
  // scalastyle:on
  StdIn.readLine()

  bindingFuture
    .flatMap(_.unbind()) // trigger unbinding from the port
    .onComplete(_ => sys.terminate()) // and shutdown when done
}
