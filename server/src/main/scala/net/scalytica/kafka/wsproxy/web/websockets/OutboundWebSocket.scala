package net.scalytica.kafka.wsproxy.web.websockets

import akka.actor.ActorSystem
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.{ActorRef, Scheduler}
import akka.http.scaladsl.model.ws.{BinaryMessage, Message, TextMessage}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{Route, ValidationRejection}
import akka.kafka.scaladsl.Consumer
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.stream.typed.scaladsl.ActorSink
import akka.util.{ByteString, Timeout}
import akka.{Done, NotUsed}
import io.circe.Encoder
import io.circe.Printer.noSpaces
import io.circe.parser.parse
import io.circe.syntax._
import net.scalytica.kafka.wsproxy._
import net.scalytica.kafka.wsproxy.admin.WsKafkaAdminClient
import net.scalytica.kafka.wsproxy.codecs.Decoders._
import net.scalytica.kafka.wsproxy.codecs.Encoders._
import net.scalytica.kafka.wsproxy.codecs.ProtocolSerdes.{
  avroCommitSerde,
  avroConsumerRecordSerde
}
import net.scalytica.kafka.wsproxy.config.Configuration.AppCfg
import net.scalytica.kafka.wsproxy.consumer.{CommitStackHandler, WsConsumer}
import net.scalytica.kafka.wsproxy.jmx.JmxManager
import net.scalytica.kafka.wsproxy.jmx.mbeans.ConsumerClientStatsProtocol._
import net.scalytica.kafka.wsproxy.logging.WithProxyLogger
import net.scalytica.kafka.wsproxy.models._
import net.scalytica.kafka.wsproxy.session.SessionHandler._
import net.scalytica.kafka.wsproxy.session.{Session, SessionHandlerProtocol}
import net.scalytica.kafka.wsproxy.streams.ProxyFlowExtras
import net.scalytica.kafka.wsproxy.web.SocketProtocol.{AvroPayload, JsonPayload}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

trait OutboundWebSocket extends ProxyFlowExtras with WithProxyLogger {

  implicit private[this] val timeout: Timeout = 3 seconds

  /** Convenience function for logging and throwing an error in a Flow */
  def logAndThrow[T](message: String, t: Throwable): T = {
    logger.error(message, t)
    throw t
  }

  /** Prepare the session for a given client */
  private[this] def initSessionForConsumer(
      serverId: WsServerId,
      groupId: WsGroupId,
      clientId: WsClientId,
      numPartitions: Int,
      sh: ActorRef[SessionHandlerProtocol.Protocol]
  )(
      implicit ec: ExecutionContext,
      scheduler: Scheduler
  ) = {
    for {
      ir     <- sh.initSession(groupId, numPartitions)
      _      <- logger.debugf(s"Session ${ir.session.consumerGroupId} ready.")
      addRes <- sh.addConsumer(groupId, clientId, serverId)
    } yield addRes
  }

  /** Setup the JMX flows for the websocket stream */
  private[this] def prepareJmx(groupId: WsGroupId, clientId: WsClientId)(
      implicit jmx: Option[JmxManager],
      as: ActorSystem
  ): ActorRef[ConsumerClientStatsCommand] = jmx
    .map { implicit j =>
      // Update the number of active consumer connections
      j.addConsumerConnection()
      // Init consumer client stats actor
      j.initConsumerClientStatsActor(clientId, groupId)
    }
    .getOrElse {
      implicit val tas = as.toTyped
      tas.ignoreRef[ConsumerClientStatsCommand]
    }

  // scalastyle:off method.length
  /**
   * Request handler for the outbound Kafka WebSocket connection.
   *
   * @param args
   *   the output arguments to pass on to the consumer.
   * @param as
   *   Implicitly provided [[ActorSystem]]
   * @param mat
   *   Implicitly provided [[Materializer]]
   * @param ec
   *   Implicitly provided [[ExecutionContext]]
   * @param sessionHandler
   *   Implicitly provided typed [[ActorRef]] for the
   *   [[SessionHandlerProtocol.Protocol]]
   * @param jmxManager
   *   Implicitly provided optional [[JmxManager]]
   * @return
   *   a [[Route]] for accessing the outbound WebSocket functionality.
   */
  def outboundWebSocket(
      args: OutSocketArgs
  )(
      implicit cfg: AppCfg,
      as: ActorSystem,
      mat: Materializer,
      ec: ExecutionContext,
      sessionHandler: ActorRef[SessionHandlerProtocol.Protocol],
      jmxManager: Option[JmxManager]
  ): Route = {
    logger.debug("Initialising outbound websocket")

    implicit val scheduler = as.scheduler.toTyped

    val wsAdminClient   = new WsKafkaAdminClient(cfg)
    val topicPartitions = wsAdminClient.topicPartitions(args.topic)

    val serverId = cfg.server.serverId
    val clientId = args.clientId
    val groupId  = args.groupId

    val consumerAddResult = initSessionForConsumer(
      serverId = serverId,
      groupId = groupId,
      clientId = clientId,
      numPartitions = topicPartitions,
      sh = sessionHandler
    )

    onSuccess(consumerAddResult) {
      case Session.ConsumerAdded(_) =>
        prepareOutboundWebSocket(args) { () =>
          // Decrease the number of active consumer connections.
          jmxManager.foreach(_.removeConsumerConnection())
          // Remove the consumer from the session handler
          sessionHandler
            .removeConsumer(groupId, clientId)
            .map(_ => Done)
            .recoverWith { case t: Throwable =>
              logger.trace("Removal failed due to an error", t)
              Future.successful(Done)
            }
        }

      case Session.ConsumerExists(_) =>
        reject(
          ValidationRejection(
            s"WebSocket for consumer ${clientId.value} in session " +
              s"${groupId.value} not established because a consumer with the" +
              " same ID is already registered."
          )
        )

      case Session.ConsumerLimitReached(s) =>
        reject(
          ValidationRejection(
            s"The maximum number of WebSockets for session ${groupId.value} " +
              s"has been reached. Limit is ${s.consumerLimit}"
          )
        )

      case Session.SessionNotFound(_) =>
        reject(
          ValidationRejection(
            s"Could not find an active session for ${groupId.value}."
          )
        )

      case wrong =>
        logger.error(
          s"Adding consumer failed with an unexpected state." +
            s" Session:\n ${wrong.session}"
        )
        failWith(
          new InternalError(
            "An unexpected error occurred when trying to establish the " +
              s"WebSocket consumer ${clientId.value} in session" +
              s" ${groupId.value}."
          )
        )
    }
  }
  // scalastyle:on

  /**
   * Actual preparation and setup of the outbound WebSocket [[Route]].
   *
   * @param args
   *   the [[OutSocketArgs]]
   * @param terminateConsumer
   *   termination logic for current websocket consumer.
   * @param cfg
   *   the implicit [[AppCfg]] to use
   * @param as
   *   the implicit untyped [[ActorSystem]] to use
   * @param mat
   *   the implicit [[Materializer]] to use
   * @param ec
   *   the implicit [[ExecutionContext]] to use
   * @param jmxManager
   *   Implicitly provided optional [[JmxManager]]
   * @return
   *   the WebSocket [[Route]]
   */
  private[this] def prepareOutboundWebSocket(
      args: OutSocketArgs
  )(terminateConsumer: () => Future[Done])(
      implicit cfg: AppCfg,
      as: ActorSystem,
      mat: Materializer,
      ec: ExecutionContext,
      jmxManager: Option[JmxManager]
  ): Route = {
    val commitHandlerRef =
      if (args.autoCommit) None
      else Some(as.spawn(CommitStackHandler.commitStack, args.clientId.value))

    implicit val statsActorRef = prepareJmx(args.groupId, args.clientId)

    // Init monitoring flows
    val jmxInFlow = jmxManager
      .map(_.consumerStatsInboundWireTap(statsActorRef))
      .getOrElse(Flow[WsCommit])

    val messageParser = args.socketPayload match {
      case JsonPayload => jsonMessageToWsCommit via jmxInFlow
      case AvroPayload => avroMessageToWsCommit via jmxInFlow
    }

    val sink   = prepareSink(args, messageParser, commitHandlerRef)
    val source = kafkaSource(args, commitHandlerRef)

    handleWebSocketMessages {
      Flow
        .fromSinkAndSourceCoupledMat(sink, source)(Keep.both)
        .watchTermination() { (m, f) =>
          val termination = for {
            done <- f
            _    <- terminateConsumer()
          } yield done

          termination.onComplete {
            case scala.util.Success(_) =>
              m._2.drainAndShutdown(Future.successful(Done))
              logger.info(
                "Outbound WebSocket connect with clientId " +
                  s"${args.clientId.value} for topic ${args.topic.value} has " +
                  "been disconnected."
              )

            case scala.util.Failure(e) =>
              m._2.drainAndShutdown(Future.successful(Done))
              logger.error(
                "Outbound WebSocket connection with clientId " +
                  s"${args.clientId.value} for topic ${args.topic.value} has " +
                  "been disconnected due to an unexpected error",
                e
              )
          }
        }
    }
  }

  /**
   * Prepares the appropriate Sink to use for incoming messages on the outbound
   * socket. The Sink is set up based on the desire to auto-commit or not.
   *
   *   - If the client requests auto-commit, all incoming messages are ignored.
   *   - If the client disables auto-commit, the Sink accepts JSON
   *     representation of [[WsCommit]] messages. These are then passed on to an
   *     Actor with [[CommitStackHandler]] behaviour.
   *
   * @param args
   *   the [[OutSocketArgs]]
   * @param messageParser
   *   parser to use for decoding incoming messages.
   * @param aref
   *   an optional [[ActorRef]] to a [[CommitStackHandler]].
   * @return
   *   a [[Sink]] for consuming [[Message]] s
   * @see
   *   [[CommitStackHandler.commitStack]]
   */
  private[this] def prepareSink(
      args: OutSocketArgs,
      messageParser: Flow[Message, WsCommit, NotUsed],
      aref: Option[ActorRef[CommitStackHandler.CommitProtocol]]
  ): Sink[Message, _] =
    aref
      .map { ar =>
        messageParser
          .map(wc => CommitStackHandler.Commit(wc))
          .to(
            ActorSink.actorRef[CommitStackHandler.CommitProtocol](
              ref = ar,
              onCompleteMessage = CommitStackHandler.Stop,
              onFailureMessage = { t: Throwable =>
                logger.error(
                  "Commit stack handler encountered an error while processing" +
                    s" commit message for client ${args.clientId.value} " +
                    s"in group ${args.groupId.value}",
                  t
                )
                CommitStackHandler.Terminate(t)
              }
            )
          )
      }
      // If no commit handler is defined, incoming messages are ignored.
      .getOrElse(Sink.ignore)

  /**
   * Converts a WebSocket [[Message]] with a JSON String payload into a
   * [[WsCommit]] for down-stream processing.
   *
   * @param mat
   *   the Materializer to use
   * @param ec
   *   the ExecutionContext to use
   * @return
   *   a [[Flow]] converting [[Message]] to String
   */
  private[this] def jsonMessageToWsCommit(
      implicit mat: Materializer,
      ec: ExecutionContext
  ): Flow[Message, WsCommit, NotUsed] =
    wsMessageToStringFlow
      .recover { case t: Throwable =>
        logAndThrow("There was an error processing a JSON message", t)
      }
      .map(msg => parse(msg).flatMap(_.as[WsCommit]))
      .recover { case t: Throwable =>
        logAndThrow(s"JSON message could not be parsed", t)
      }
      .collect { case Right(res) => res }

  /**
   * Converts a WebSocket [[Message]] with an Avro payload into a [[WsCommit]]
   * for down-stream processing.
   *
   * @param mat
   *   the [[Materializer]] to use
   * @param ec
   *   the [[ExecutionContext]] to use
   * @return
   *   a [[Flow]] converting [[Message]] to String
   */
  private[this] def avroMessageToWsCommit(
      implicit mat: Materializer,
      ec: ExecutionContext
  ): Flow[Message, WsCommit, NotUsed] =
    wsMessageToByteStringFlow
      .recover { case t: Throwable =>
        logAndThrow("There was an error processing an Avro message", t)
      }
      .map(msg => avroCommitSerde.deserialize(msg.toArray[Byte]))
      .recover { case t: Throwable =>
        logAndThrow(s"Avro message could not be deserialised", t)
      }
      .map(WsCommit.fromAvro)

  /**
   * The Kafka [[Source]] where messages are consumed from the topic.
   *
   * @param args
   *   the output arguments to pass on to the consumer.
   * @param commitHandlerRef
   *   an optional [[ActorRef]] to a [[CommitStackHandler]].
   * @param cfg
   *   the application configuration.
   * @param as
   *   the actor system to use.
   * @param jmxManager
   *   Implicitly provided optional [[JmxManager]]
   * @param clientStatsActorRef
   *   Implicitly provided ActorRef for an instance of
   *   {{ConsumerClientStatsMXBeanActor}}
   * @return
   *   a [[Source]] producing [[TextMessage]] s for the outbound WebSocket.
   */
  private[this] def kafkaSource[K, V](
      args: OutSocketArgs,
      commitHandlerRef: Option[ActorRef[CommitStackHandler.CommitProtocol]]
  )(
      implicit cfg: AppCfg,
      as: ActorSystem,
      jmxManager: Option[JmxManager],
      clientStatsActorRef: ActorRef[ConsumerClientStatsCommand]
  ): Source[Message, Consumer.Control] = {
    val keyTpe = args.keyType.getOrElse(Formats.NoType)
    val valTpe = args.valType

    type Key = keyTpe.Aux
    type Val = valTpe.Aux

    // Init monitoring flow
    val jmxOutFlow = jmxManager
      .map(_.consumerStatsOutboundWireTap[Key, Val](clientStatsActorRef))
      .getOrElse(Flow[WsConsumerRecord[Key, Val]])

    // Kafka serdes
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
        .consumeAutoCommit[Key, Val](args)
        .map(cr => enrichWithFormatType(args, cr))
        .via(jmxOutFlow)
        .map(cr => msgConverter(cr))
    } else {
      // if auto-commit is disabled, we need to ensure messages are sent to a
      // commit handler so its offset can be committed manually by the client.
      val sink = commitHandlerRef.map(manualCommitSink).getOrElse(Sink.ignore)

      WsConsumer
        .consumeManualCommit[Key, Val](args)
        .map(cr => enrichWithFormatType(args, cr))
        .via(jmxOutFlow)
        .alsoTo(sink) // also send each message to the commit handler sink
        .map(cr => msgConverter(cr))
    }
  }

  private[this] def enrichWithFormatType[K, V](
      args: OutSocketArgs,
      cr: WsConsumerRecord[K, V]
  ): WsConsumerRecord[K, V] =
    args.keyType
      .map(kt => cr.withKeyFormatType(kt))
      .getOrElse(cr)
      .withValueFormatType(args.valType)

  /**
   * Helper function that translates a [[WsConsumerRecord]] into the correct
   * type of [[Message]], based on the {{{args}}}.
   *
   * @param args
   *   [[OutSocketArgs]] for building the outbound WebSocket.
   * @param recEnc
   *   JSON encoder for [[WsConsumerRecord]]
   * @tparam K
   *   the key type
   * @tparam V
   *   the value type
   * @return
   *   the data as a [[Message]].
   */
  private[this] def socketFormatConverter[K, V](
      args: OutSocketArgs
  )(
      implicit recEnc: Encoder[WsConsumerRecord[K, V]]
  ): WsConsumerRecord[K, V] => Message =
    args.socketPayload match {
      case AvroPayload =>
        cr =>
          val byteString = ByteString(
            avroConsumerRecordSerde
              .serialize(args.topic.value, cr.toAvroRecord[K, V])
          )
          BinaryMessage(byteString)
      case JsonPayload =>
        cr => TextMessage(cr.asJson.printWith(noSpaces))
    }

  /**
   * Builds a Flow that sends consumed [[WsConsumerRecord]] s to the relevant
   * instance of [[CommitStackHandler]] actor Sink, which stashed the record so
   * that its offset can be committed later.
   *
   * @param ref
   *   the [[ActorRef]] for the [[CommitStackHandler]]
   * @tparam K
   *   the type of the record key
   * @tparam V
   *   the type of the record value
   * @return
   *   a commit [[Sink]] for [[WsConsumerRecord]] messages
   */
  private[this] def manualCommitSink[K, V](
      ref: ActorRef[CommitStackHandler.CommitProtocol]
  ): Sink[WsConsumerRecord[K, V], NotUsed] = {
    Flow[WsConsumerRecord[K, V]]
      .map(rec => CommitStackHandler.Stash(rec))
      .to(
        ActorSink.actorRef[CommitStackHandler.CommitProtocol](
          ref = ref,
          onCompleteMessage = CommitStackHandler.Continue,
          onFailureMessage = _ => CommitStackHandler.Continue
        )
      )
  }

}
