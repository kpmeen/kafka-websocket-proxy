package net.scalytica.kafka.wsproxy

import akka.Done
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.kafka.scaladsl.Consumer
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import com.typesafe.scalalogging.Logger
import io.circe.Printer.noSpaces
import io.circe.syntax._
import net.scalytica.kafka.wsproxy.BasicSerdes._
import net.scalytica.kafka.wsproxy.Encoders._
import net.scalytica.kafka.wsproxy.consumer.WsConsumer
import net.scalytica.kafka.wsproxy.producer.WsProducer

import scala.concurrent.{ExecutionContext, Future}
import scala.io.StdIn

object Server extends App {

  private[this] val logger = Logger(this.getClass)

  implicit private[this] val sys: ActorSystem       = ActorSystem()
  implicit private[this] val mat: ActorMaterializer = ActorMaterializer()
  implicit private[this] val ctx: ExecutionContext  = sys.dispatcher

  private[this] val port = 8080

  implicit private[this] def errorHandler: ExceptionHandler = ExceptionHandler {
    case t: Throwable =>
      extractUri { uri =>
        logger.warn(s"Request to $uri could not be handled normally", t)
        complete(HttpResponse(InternalServerError, entity = t.getMessage))
      }
  }

  // TODO: Create type classes for different key value combos to handle message
  //       parsing. See WsProducer.stringKeyValueParser for sample converter
  private[this] def kafkaPlainKeyValueSink(topic: String) = {
    WsProducer.sink[String, String](topic)
  }

  /**
   * The Kafka Source where messages are consumed.
   *
   * @param topic The topic to subscribe to
   * @param clientId The clientId to use with the Kafka client.
   * @param groupId The groupId to use with the Kafka client.
   * @return a [[Source]] producing [[TextMessage]]s for the outbound WebSocket.
   */
  private[this] def kafkaSource(
      topic: String,
      clientId: String,
      groupId: Option[String] = None,
  ): Source[TextMessage.Strict, Consumer.Control] = {
    WsConsumer
      .kafkaSource[String, String](topic, clientId, groupId)
      .map(cr => cr -> TextMessage(cr.asJson.pretty(noSpaces)))
      .mapAsync(1) { case (rec, msg) => rec.commit().map(_ => msg) }
  }

  /**
   * Request handler for the Kafka WebSocket connection.
   *
   * @param sink The Sink for inbound traffic. Producing messages.
   * @param source The Source for outbound traffic. Consuming messages.
   * @return a [[Route]] for accessing the WebSocket functionality.
   */
  private[this] def handleKafkaWebSocket(
      sink: Sink[Message, _],
      source: Source[TextMessage.Strict, Consumer.Control]
  ): Route = {
    handleWebSocketMessages {
      Flow
        .fromSinkAndSourceCoupledMat(sink, source)(Keep.both)
        .watchTermination() { (m, f) =>
          f.onComplete {
            case scala.util.Success(_) =>
              m._2.drainAndShutdown(Future.successful(Done))
              logger.info(s"Client has disconnected.")
            case scala.util.Failure(e) =>
              m._2.drainAndShutdown(Future.successful(Done))
              logger.error("Disconnection failure", e)
          }
        }
    }
  }

  /**
   * @return Directive extracting query parameters for the outbound (consuming)
   *         socket communication.
   */
  private[this] def outParams: Directive[Tuple1[OutSocketParams]] =
    parameters(
      'clientId,
      'groupId ?,
      'outTopic,
      'outKeyType ?,
      'outValType,
      'offsetResetStrategy ? "earliest"
    ).tmap(
      t => OutSocketParams.fromQueryParams(t._1, t._2, t._3, t._4, t._5, t._6)
    )

  /**
   * @return Directive extracting query parameters for the inbound (producer)
   *         socket communication.
   */
  private[this] def optInParams: Directive[Tuple1[Option[InSocketParams]]] =
    parameters('inTopic ?, 'inKeyType ?, 'inValType ?)
      .tmap(t => InSocketParams.fromOptQueryParams(t._1, t._2, t._3))

  // Endpoint route(s) providing access to the Kafka WebSocket
  private[this] val route =
    path("socket") {
      (outParams & optInParams) { (out, in) =>
        handleKafkaWebSocket(
          sink = in
            .map(isp => kafkaPlainKeyValueSink(isp.topic))
            .getOrElse(Sink.ignore),
          source = kafkaSource(out.topic, out.clientId, out.groupId)
        )
      }
    }

  private[this] val bindingFuture = Http().bindAndHandle(
    handler = route,
    interface = "localhost",
    port = port
  )

  // scalastyle:off
  println(
    """Server online at http://localhost:8080/
      |Press RETURN to stop...""".stripMargin
  )
  StdIn.readLine()
  // scalastyle:on

  bindingFuture
    .flatMap(_.unbind()) // trigger unbinding from the port
    .onComplete(_ => sys.terminate()) // and shutdown when done
}
