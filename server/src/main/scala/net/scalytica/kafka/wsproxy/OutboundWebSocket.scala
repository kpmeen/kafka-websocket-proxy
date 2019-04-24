package net.scalytica.kafka.wsproxy

import akka.actor.ActorSystem
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.adapter._
import akka.http.scaladsl.model.ws.{BinaryMessage, Message, TextMessage}
import akka.http.scaladsl.server.Directives.handleWebSocketMessages
import akka.http.scaladsl.server.Route
import akka.kafka.scaladsl.Consumer
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.stream.typed.scaladsl.ActorSink
import akka.util.ByteString
import akka.{Done, NotUsed}
import com.typesafe.scalalogging.Logger
import io.circe.Encoder
import io.circe.Printer.noSpaces
import io.circe.parser.parse
import io.circe.syntax._
import net.scalytica.kafka.wsproxy.Configuration.AppCfg
import net.scalytica.kafka.wsproxy.SocketProtocol.{AvroPayload, JsonPayload}
import net.scalytica.kafka.wsproxy.avro.SchemaTypes._
import net.scalytica.kafka.wsproxy.avro.SchemaTypes.Implicits._
import net.scalytica.kafka.wsproxy.codecs.Decoders._
import net.scalytica.kafka.wsproxy.codecs.Encoders._
import net.scalytica.kafka.wsproxy.codecs.WsProxyAvroSerde
import net.scalytica.kafka.wsproxy.consumer.{CommitHandler, WsConsumer}
import net.scalytica.kafka.wsproxy.models.{
  Formats,
  OutSocketArgs,
  WsCommit,
  WsConsumerRecord
}
import org.apache.kafka.common.serialization.Serializer

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

trait OutboundWebSocket extends WithSchemaRegistryConfig {

  private[this] val logger = Logger(getClass)

  private[this] def avroCommitSerde(implicit cfg: AppCfg) =
    cfg.server.schemaRegistryUrl
      .map { url =>
        WsProxyAvroSerde[AvroCommit](schemaRegistryCfg(url))
      }
      .getOrElse(WsProxyAvroSerde[AvroCommit]())

  private[this] def avroConsumerRecordSerde(implicit cfg: AppCfg) =
    cfg.server.schemaRegistryUrl
      .map { url =>
        WsProxyAvroSerde[AvroConsumerRecord](schemaRegistryCfg(url))
      }
      .getOrElse(WsProxyAvroSerde[AvroConsumerRecord]())

  /**
   * Request handler for the outbound Kafka WebSocket connection.
   *
   * @param args the output arguments to pass on to the consumer.
   * @return a [[Route]] for accessing the outbound WebSocket functionality.
   */
  def outboundWebSocket(
      args: OutSocketArgs
  )(
      implicit
      cfg: AppCfg,
      as: ActorSystem,
      mat: ActorMaterializer,
      ec: ExecutionContext
  ): Route = {
    logger.debug("Initialising outbound websocket")

    val commitHandlerRef =
      if (args.autoCommit) None
      else Some(as.spawn(CommitHandler.commitStack, args.clientId))

    val messageParser = args.socketPayload match {
      case JsonPayload => messageToString
      case AvroPayload => messageToByteString
    }

    val sink = prepareSink(messageParser, commitHandlerRef)
    // TODO: Setup JSON or Avro sink based on the value of output
    //       format type in OutSocketArgs.
    val source = kafkaSource(args, commitHandlerRef)

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
   * Prepares the appropriate Sink to use for incoming messages on the outbound
   * socket. The Sink is set up based on the desire to auto-commit or not.
   *
   * - If the client requests auto-commit, all incoming messages are ignored.
   * - If the client disables auto-commit, the Sink accepts JSON representation
   *   of [[WsCommit]] messages. These are then passed on to an Actor with
   *   [[CommitHandler]] behaviour.
   *
   * @param messageParser parser to use for decoding incoming messages.
   * @param aref an optional [[ActorRef]] to a [[CommitHandler]].
   * @param mat  the [[ActorMaterializer]] to use
   * @param ec   the [[ExecutionContext]] to use
   * @return a [[Sink]] for consuming [[Message]]s
   * @see [[CommitHandler.commitStack]]
   */
  private[this] def prepareSink(
      messageParser: Flow[Message, WsCommit, NotUsed],
      aref: Option[ActorRef[CommitHandler.Protocol]]
  )(
      implicit
      mat: ActorMaterializer,
      ec: ExecutionContext
  ): Sink[Message, _] =
    aref
      .map { ar =>
        messageParser
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
      // If no commit handler is defined, incoming messages are ignored.
      .getOrElse(Sink.ignore)

  /**
   * Converts a WebSocket [[Message]] into a String for down-stream processing.
   *
   * @param mat the Materializer to use
   * @param ec  the ExecutionContext to use
   * @return a [[Flow]] converting [[Message]] to String
   */
  private[this] def messageToString(
      implicit
      mat: ActorMaterializer,
      ec: ExecutionContext
  ): Flow[Message, WsCommit, NotUsed] =
    Flow[Message]
      .mapConcat {
        case tm: TextMessage   => TextMessage(tm.textStream) :: Nil
        case bm: BinaryMessage => bm.dataStream.runWith(Sink.ignore); Nil
      }
      .mapAsync(1)(_.toStrict(2 seconds).map(_.text))
      .map(msg => parse(msg).flatMap(_.as[WsCommit]))
      .collect { case Right(res) => res }

  private[this] def messageToByteString(
      implicit
      cfg: AppCfg,
      mat: ActorMaterializer,
      ec: ExecutionContext
  ): Flow[Message, WsCommit, NotUsed] =
    Flow[Message]
      .mapConcat {
        case tm: TextMessage   => tm.textStream.runWith(Sink.ignore); Nil
        case bm: BinaryMessage => BinaryMessage(bm.dataStream) :: Nil
      }
      .mapAsync(1)(_.toStrict(2 seconds).map(_.data))
      .map(msg => avroCommitSerde.deserialize("", msg.toArray[Byte]))
      .map(WsCommit.fromAvro)

  /**
   * The Kafka Source where messages are consumed.
   *
   * @param args the output arguments to pass on to the consumer.
   * @param cfg the application configuration.
   * @param as the actor system to use.
   * @return a [[Source]] producing [[TextMessage]]s for the outbound WebSocket.
   */
  private[this] def kafkaSource(
      args: OutSocketArgs,
      commitHandlerRef: Option[ActorRef[CommitHandler.Protocol]]
  )(
      implicit
      cfg: AppCfg,
      as: ActorSystem
  ): Source[Message, Consumer.Control] = {
    val keyTpe = args.keyType.getOrElse(Formats.NoType)
    val valTpe = args.valType

    type Key = keyTpe.Aux
    type Val = valTpe.Aux

    // Kafka serdes
    implicit val keySer = keyTpe.serializer
    implicit val valSer = valTpe.serializer
    implicit val keyDes = keyTpe.deserializer
    implicit val valDes = valTpe.deserializer
    // JSON encoders
    implicit val keyEnc: Encoder[Key] = keyTpe.encoder
    implicit val valEnc: Encoder[Val] = valTpe.encoder
    implicit val recEnc: Encoder[WsConsumerRecord[Key, Val]] =
      wsConsumerRecordEncoder[Key, Val]

    val msgConverter = socketFormatConverter[Key, Val](args)

    if (args.autoCommit) {
      // if auto-commit is enabled, we don't need to handle manual commits.
      WsConsumer
        .consumeAutoCommit[Key, Val](args.topic, args.clientId, args.groupId)
        .map(cr => msgConverter(cr))
    } else {
      // if auto-commit is disabled, we need to ensure messages are sent to a
      // commit handler so its offset can be committed manually by the client.
      val sink = commitHandlerRef.map(manualCommitSink).getOrElse(Sink.ignore)

      WsConsumer
        .consumeManualCommit[Key, Val](args.topic, args.clientId, args.groupId)
        .alsoTo(sink) // also send each message to the commit handler sink
        .map(cr => msgConverter(cr))
    }
  }

  /**
   * Helper function that translates a [[WsConsumerRecord]] into the correct
   * type of [[Message]], based on the {{{args}}}.
   *
   * @param args [[OutSocketArgs]] for building the outbound WebSocket.
   * @param keySer the key serializer
   * @param valSer the value serializer
   * @param keyEnc JSON encoder for the key type
   * @param valEnc JSON encoder for the value type
   * @param recEnc JSON encoder for [[WsConsumerRecord]]
   * @tparam K the key type
   * @tparam V the value type
   * @return the data as a [[Message]].
   */
  private[this] def socketFormatConverter[K, V](
      args: OutSocketArgs
  )(
      implicit
      cfg: AppCfg,
      keySer: Serializer[K],
      valSer: Serializer[V],
      keyEnc: Encoder[K],
      valEnc: Encoder[V],
      recEnc: Encoder[WsConsumerRecord[K, V]]
  ): WsConsumerRecord[K, V] => Message = args.socketPayload match {
    case AvroPayload => { cr =>
      val byteString = ByteString(
        avroConsumerRecordSerde.serialize(args.topic, cr.toAvroRecord[K, V])
      )
      BinaryMessage(byteString)
    }
    case JsonPayload => { cr =>
      TextMessage(cr.asJson.pretty(noSpaces))
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

}
