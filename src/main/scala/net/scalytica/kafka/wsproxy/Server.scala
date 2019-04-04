package net.scalytica.kafka.wsproxy

import akka.actor.ActorSystem
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.adapter._
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.ws.{BinaryMessage, Message, TextMessage}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.kafka.scaladsl.Consumer
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.stream.typed.scaladsl.ActorSink
import akka.{Done, NotUsed}
import com.typesafe.scalalogging.Logger
import io.circe.Encoder
import io.circe.Printer.noSpaces
import io.circe.parser._
import io.circe.syntax._
import net.scalytica.kafka.wsproxy.Decoders._
import net.scalytica.kafka.wsproxy.Encoders._
import net.scalytica.kafka.wsproxy.Formats.FormatType
import net.scalytica.kafka.wsproxy.consumer.{CommitHandler, WsConsumer}
import net.scalytica.kafka.wsproxy.producer.WsProducer
import net.scalytica.kafka.wsproxy.records.{
  WsCommit,
  WsConsumerRecord,
  WsProducerResult
}
import org.apache.kafka.clients.consumer.OffsetResetStrategy

import scala.concurrent.duration._
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
      args: OutSocketArgs,
      commitHandlerRef: Option[ActorRef[CommitHandler.Protocol]]
  ): Source[TextMessage, Consumer.Control] = {
    val keyTpe = args.keyType.getOrElse(Formats.NoType)
    val valTpe = args.valType

    // Lifting the FormatType types into aliases for ease of use.
    type Key   = keyTpe.Aux
    type Value = valTpe.Aux

    // Kafka deserializers
    implicit val keySer = keyTpe.deserializer
    implicit val valSer = valTpe.deserializer
    // JSON encoders
    implicit val keyEnc: Encoder[Key]   = keyTpe.encoder
    implicit val valEnc: Encoder[Value] = valTpe.encoder
    implicit val recEnc: Encoder[WsConsumerRecord[Key, Value]] =
      wsConsumerRecordToJson[Key, Value]

    if (args.autoCommit) {
      // if auto-commit is enabled, we don't need to handle manual commits.
      WsConsumer
        .consumeAutoCommit[Key, Value](args.topic, args.clientId, args.groupId)
        .map(cr => TextMessage(cr.asJson.pretty(noSpaces)))
    } else {
      // if auto-commit is disabled, we need to ensure messages are sent to a
      // commit handler so its offset can be committed manually by the client.
      val commitSink =
        commitHandlerRef.map(manualCommitSink).getOrElse(Sink.ignore)

      WsConsumer
        .consumeManualCommit[Key, Value](
          args.topic,
          args.clientId,
          args.groupId
        )
        .alsoTo(commitSink) // also send each message to the commit handler sink
        .map(cr => TextMessage(cr.asJson.pretty(noSpaces)))
    }
  }

  /**
   * Builds a Flow that sends consumed [[WsConsumerRecord]]s to the relevant
   * instance of [[CommitHandler]] actor Sink, which stashed the record so that
   * its offset can be committed later.
   *
   * @param ref the [[ActorRef]] for the [[CommitHandler]]
   * @tparam K the type of the record key
   * @tparam V the type of the record value
   * @return a commit [[Sink]] for [[WsConsumerRecord]] messages
   */
  private[this] def manualCommitSink[K, V](
      ref: ActorRef[CommitHandler.Protocol]
  ): Sink[WsConsumerRecord[K, V], NotUsed] = {
    Flow[WsConsumerRecord[K, V]]
      .map(rec => CommitHandler.Stash(rec))
      .to(
        ActorSink.actorRef[CommitHandler.Protocol](
          ref = ref,
          onCompleteMessage = CommitHandler.Continue,
          onFailureMessage = _ => CommitHandler.Continue
        )
      )
  }

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

    val aref =
      if (args.autoCommit) None
      else Some(sys.spawn(CommitHandler.commitStack, args.clientId))

    val sink = aref
      .map { ar =>
        Flow[Message]
          .mapConcat {
            case tm: TextMessage   => TextMessage(tm.textStream) :: Nil
            case bm: BinaryMessage => bm.dataStream.runWith(Sink.ignore); Nil
          }
          .mapAsync(1)(_.toStrict(2 seconds).map(_.text))
          .map(msg => parse(msg).flatMap(_.as[WsCommit]))
          .collect { case Right(res) => res }
          .map(wc => CommitHandler.Commit(wc))
          .to(
            ActorSink.actorRef[CommitHandler.Protocol](
              ref = ar,
              onCompleteMessage = CommitHandler.Stop,
              onFailureMessage = { t: Throwable =>
                logger.error("An error occurred processing commit message", t)
                CommitHandler.Continue
              }
            )
          )
      }
      .getOrElse(Sink.ignore)

    val source = kafkaSource(args, aref)

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
