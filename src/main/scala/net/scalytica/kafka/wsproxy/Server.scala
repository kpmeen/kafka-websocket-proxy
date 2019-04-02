package net.scalytica.kafka.wsproxy

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.kafka.scaladsl.Consumer
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.{Done, NotUsed}
import com.typesafe.scalalogging.Logger
import io.circe.Encoder
import io.circe.Printer.noSpaces
import io.circe.syntax._
import net.scalytica.kafka.wsproxy.Encoders._
import net.scalytica.kafka.wsproxy.Formats.FormatType
import net.scalytica.kafka.wsproxy.consumer.WsConsumer
import net.scalytica.kafka.wsproxy.producer.WsProducer
import net.scalytica.kafka.wsproxy.records.WsProducerResult
import org.apache.kafka.clients.consumer.OffsetResetStrategy

import scala.concurrent.{ExecutionContext, Future}
import scala.io.StdIn
import scala.util.Try

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

  /**
   * Creates a WebSocket Sink for the inbound channel.
   *
   * @param args the input arguments to pass on to the producer.
   * @return a [[WsProducer.produce]] for the provided args.
   */
  private[this] def kafkaSink(
      args: InSocketArgs
  ): Flow[Message, WsProducerResult, NotUsed] = {
    val ktpe = args.keyType.getOrElse(Formats.NoType)

    implicit val keySer = ktpe.serializer
    implicit val valSer = args.valType.serializer
    implicit val keyDec = ktpe.decoder
    implicit val valDec = args.valType.decoder

    WsProducer.produce[ktpe.Aux, args.valType.Aux](args)
  }

  /**
   * The Kafka Source where messages are consumed.
   *
   * @param args the output arguments to pass on to the consumer.
   * @return a [[Source]] producing [[TextMessage]]s for the outbound WebSocket.
   */
  private[this] def kafkaSource(
      args: OutSocketArgs
  ): Source[TextMessage, Consumer.Control] = {
    val ktpe = args.keyType.getOrElse(Formats.NoType)

    // Kafka deserializers
    implicit val keySer = ktpe.deserializer
    implicit val valSer = args.valType.deserializer
    // JSON encoders
    implicit val keyEnc: Encoder[ktpe.Aux]         = ktpe.encoder
    implicit val valEnc: Encoder[args.valType.Aux] = args.valType.encoder

    implicit val recEnc = wsConsumerRecordToJson[ktpe.Aux, args.valType.Aux]

    if (args.autoCommit) {
      WsConsumer
        .consumeAutoCommit[ktpe.Aux, args.valType.Aux](
          args.topic,
          args.clientId,
          args.groupId
        )
        .map(cr => TextMessage(cr.asJson.pretty(noSpaces)))
    } else {
      WsConsumer
        .consumeManualCommit[ktpe.Aux, args.valType.Aux](
          args.topic,
          args.clientId,
          args.groupId
        )
        .map(cr => cr -> TextMessage(cr.asJson.pretty(noSpaces)))
        // FIXME: See below FIXME comment...
        .mapAsync(1) { case (rec, msg) => rec.commit().map(_ => msg) }
    }
  }

  /*
    FIXME: This must be changed to allow for reading inbound messages that
           may contain offset commit messages! The following may be an option
           to explore:
           ---
           Committer.sink(CommitterSettings(as))
           ---
           The challenge is to keep track of the Committable messages that
           knows the actual consumer ActorRef, and hence knows how to call
           the commit function.
           One solution may be to keep a dedicated Actor per outbound connection
           with manual commits, that keeps track of the actual Committable to
           be able to call the commit function.
   */
  /**
   * Request handler for the outbound Kafka WebSocket connection.
   *
   * @param args the output arguments to pass on to the consumer.
   * @return a [[Route]] for accessing the outbound WebSocket functionality.
   */
  private[this] def outboundWebSocket(
      args: OutSocketArgs
  ): Route = {
    logger.debug("Initialising outbound websocket")

    val sink   = Sink.ignore
    val source = kafkaSource(args)

    handleWebSocketMessages {
      Flow
        .fromSinkAndSourceCoupledMat(sink, source)(Keep.both)
        .watchTermination() { (m, f) =>
          f.onComplete {
            case scala.util.Success(_) =>
              m._2.drainAndShutdown(Future.successful(Done))
              logger.info(s"Consumer client has disconnected.")
            case scala.util.Failure(e) =>
              m._2.drainAndShutdown(Future.successful(Done))
              logger.error("Disconnection failure", e)
          }
        }
    }
  }

  /**
   * Request handler for the inbound Kafka WebSocket connection.
   *
   * @param args the input arguments to pass on to the producer.
   * @return a [[Route]] for accessing the inbound WebSocket functionality.
   */
  private[this] def inboundWebSocket(
      args: InSocketArgs
  ): Route = handleWebSocketMessages {
    logger.debug("Initialising inbound websocket")

    kafkaSink(args).map(res => TextMessage.Strict(res.asJson.pretty(noSpaces)))
  }

  // Unmarshaller for FormatType query params
  implicit val formatTypeUnmarshaller: Unmarshaller[String, FormatType] =
    Unmarshaller.strict[String, FormatType] { str =>
      Option(str).map(FormatType.unsafeFromString).getOrElse {
        throw Unmarshaller.NoContentException
      }
    }

  // Unmarshaller for OffsetResetStrategy params
  implicit val offResetUnmarshaller: Unmarshaller[String, OffsetResetStrategy] =
    Unmarshaller.strict[String, OffsetResetStrategy] { str =>
      Try(OffsetResetStrategy.valueOf(str.toUpperCase)).getOrElse {
        throw new IllegalArgumentException(
          s"$str is not a valid offset reset strategy"
        )
      }
    }

  /**
   * @return Directive extracting query parameters for the outbound (consuming)
   *         socket communication.
   */
  private[this] def outParams: Directive[Tuple1[OutSocketArgs]] =
    parameters(
      (
        'clientId,
        'groupId ?,
        'outTopic,
        'outKeyType.as[FormatType] ?,
        'outValType.as[FormatType],
        'offsetResetStrategy
          .as[OffsetResetStrategy] ? OffsetResetStrategy.EARLIEST,
        'rate.as[Int] ?,
        'batchSize.as[Int] ?,
        'autoCommit.as[Boolean] ? true
      )
    ).tmap { t =>
      OutSocketArgs.fromQueryParams(
        clientId = t._1,
        groupId = t._2,
        topicName = t._3,
        keyTpe = t._4,
        valTpe = t._5,
        offsetResetStrategy = t._6,
        rateLimit = t._7,
        batchSize = t._8,
        autoCommit = t._9
      )
    }

  /**
   * @return Directive extracting query parameters for the inbound (producer)
   *         socket communication.
   */
  private[this] def inParams: Directive[Tuple1[InSocketArgs]] =
    parameters(
      (
        'inTopic,
        'inKeyType.as[FormatType] ?,
        'inValType.as[FormatType]
      )
    ).tmap { t =>
      InSocketArgs.fromQueryParams(t._1, t._2, t._3)
    }

  /** Endpoint route(s) providing access to the Kafka WebSocket */
  private[this] val route =
    pathPrefix("socket") {
      path("in") {
        inParams(inboundWebSocket)
      } ~
        path("out") {
          outParams(outboundWebSocket)
        }
    }

  /** Bind to network interface and port, starting the server */
  private[this] val bindingFuture = Http().bindAndHandle(
    handler = route,
    interface = "localhost",
    port = port
  )

  // scalastyle:off
  println(
    s"""Server online at http://localhost:$port/
       |Press RETURN to stop...""".stripMargin
  )
  StdIn.readLine()
  // scalastyle:on

  /** Unbind from the network interface and port, shutting down the server. */
  bindingFuture.flatMap(_.unbind()).onComplete(_ => sys.terminate())
}
