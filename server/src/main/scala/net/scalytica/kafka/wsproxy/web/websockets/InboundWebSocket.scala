package net.scalytica.kafka.wsproxy.web.websockets

import akka.Done
import akka.actor.ActorSystem
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.{ActorRef, Scheduler}
import akka.http.scaladsl.model.ws.{BinaryMessage, Message, TextMessage}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.Materializer
import akka.stream.scaladsl.Flow
import akka.util.{ByteString, Timeout}
import io.circe.Printer.noSpaces
import io.circe.syntax._
import net.scalytica.kafka.wsproxy._
import net.scalytica.kafka.wsproxy.auth.{JwtValidationTickerFlow, OpenIdClient}
import net.scalytica.kafka.wsproxy.codecs.Encoders._
import net.scalytica.kafka.wsproxy.codecs.ProtocolSerdes.{
  avroProducerRecordSerde,
  avroProducerResultSerde
}
import net.scalytica.kafka.wsproxy.config.Configuration.AppCfg
import net.scalytica.kafka.wsproxy.errors.{
  RequestValidationError,
  UnexpectedError
}
import net.scalytica.kafka.wsproxy.jmx.JmxManager
import net.scalytica.kafka.wsproxy.jmx.mbeans.ProducerClientStatsProtocol._
import net.scalytica.kafka.wsproxy.logging.WithProxyLogger
import net.scalytica.kafka.wsproxy.models._
import net.scalytica.kafka.wsproxy.producer.WsProducer
import net.scalytica.kafka.wsproxy.session.SessionHandler._
import net.scalytica.kafka.wsproxy.session._
import net.scalytica.kafka.wsproxy.web.SocketProtocol.{AvroPayload, JsonPayload}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

trait InboundWebSocket extends WithProxyLogger {

  implicit private[this] val timeout: Timeout = 10 seconds

  private[this] def initSessionForProducer(
      serverId: WsServerId,
      fullProducerId: FullProducerId,
      maxConnections: Int,
      sh: ActorRef[SessionHandlerProtocol.Protocol]
  )(
      implicit cfg: AppCfg,
      ec: ExecutionContext,
      scheduler: Scheduler
  ): Future[SessionOpResult] = {
    if (cfg.producer.sessionsEnabled) {
      for {
        ir <- sh.initProducerSession(fullProducerId.producerId, maxConnections)
        _  <- log.debugf(s"Session ${ir.session.sessionId} is ready")
        addRes <- {
          if (fullProducerId.instanceId.nonEmpty) {
            sh.addProducer(fullProducerId, serverId)
          } else {
            Future.successful(ProducerInstanceMissingId(ir.session))
          }
        }
      } yield addRes
    } else {
      Future.successful(ProducerSessionsDisabled)
    }
  }

  private[this] def prepareJmx(
      fullProducerId: FullProducerId
  )(
      implicit jmx: Option[JmxManager],
      as: ActorSystem
  ): ActorRef[ProducerClientStatsCommand] = jmx
    .map { j =>
      j.addProducerConnection()
      j.initProducerClientStatsActor(fullProducerId)
    }
    .getOrElse {
      implicit val tas = as.toTyped
      tas.ignoreRef[ProducerClientStatsCommand]
    }

  /**
   * Request handler for the inbound Kafka WebSocket connection, with a Kafka
   * producer as the Sink.
   *
   * @param args
   *   the input arguments to pass on to the producer.
   * @param cfg
   *   Implicitly provided [[AppCfg]]
   * @param as
   *   Implicitly provided [[ActorSystem]]
   * @param mat
   *   Implicitly provided [[Materializer]]
   * @param maybeOpenIdClient
   *   Implicitly provided Option that contains an [[OpenIdClient]] if OIDC is
   *   enabled.
   * @param jmxManager
   *   Implicitly provided optional [[JmxManager]]
   * @return
   *   a [[Route]] for accessing the inbound WebSocket functionality.
   * @see
   *   [[WsProducer.produceJson]]
   */
  // scalastyle:off method.length
  def inboundWebSocket(
      args: InSocketArgs
  )(
      implicit cfg: AppCfg,
      maybeOpenIdClient: Option[OpenIdClient],
      as: ActorSystem,
      mat: Materializer,
      ec: ExecutionContext,
      sessionHandler: ActorRef[SessionHandlerProtocol.Protocol],
      jmxManager: Option[JmxManager]
  ): Route = {
    log.debug(
      s"Initialising inbound WebSocket for topic ${args.topic.value}" +
        s" with payload ${args.socketPayload}"
    )
    implicit val scheduler = as.scheduler.toTyped

    val serverId       = cfg.server.serverId
    val producerId     = args.producerId
    val sessionId      = SessionId(producerId)
    val fullProducerId = FullProducerId(producerId, args.instanceId)
    val prodLimitCfg   = cfg.producer.limits.forProducer(producerId)
    val maxCons = prodLimitCfg
      .flatMap(_.maxConnections)
      .getOrElse(cfg.producer.limits.defaultMaxConnectionsPerClient)

    val producerAddResult = initSessionForProducer(
      serverId = serverId,
      fullProducerId = fullProducerId,
      maxConnections = maxCons,
      sh = sessionHandler
    )

    onSuccess(producerAddResult) {
      case InstanceAdded(_) | ProducerSessionsDisabled =>
        prepareInboundWebSocket(fullProducerId, args) { () =>
          jmxManager.foreach(_.removeProducerConnection())
          if (cfg.producer.sessionsEnabled) {
            // Remove the producer from the session handler
            sessionHandler
              .removeProducer(sessionId, fullProducerId, serverId)
              .map(_ => Done)
              .recoverWith { case t: Throwable =>
                log.trace("Producer removal failed due to an error", t)
                Future.successful(Done)
              }
          } else {
            Future.successful(Done)
          }
        }

      case ProducerInstanceMissingId(_) =>
        throw RequestValidationError(
          s"WebSocket for producer ${fullProducerId.value} in session " +
            s"${sessionId.value} not established because instanceId could " +
            "not be found in query parameters."
        )

      case InstanceExists(_) =>
        throw RequestValidationError(
          s"WebSocket for producer ${fullProducerId.value} in session " +
            s"${sessionId.value} not established because a producer " +
            "instance with the same ID is already registered."
        )

      case InstanceLimitReached(s) =>
        throw RequestValidationError(
          s"The max number of WebSockets for session ${sessionId.value} " +
            s"has been reached. Limit is ${s.maxConnections}"
        )

      case SessionNotFound(_) =>
        throw RequestValidationError(
          s"Could not find an active session for ${sessionId.value}."
        )

      case wrong =>
        log.error(
          s"Adding producer failed with an unexpected state." +
            s" Session:\n ${wrong.session}"
        )
        throw UnexpectedError(
          "An unexpected error occurred when trying to establish the " +
            s"WebSocket producer ${fullProducerId.value} in session" +
            s" ${sessionId.value}."
        )
    }
  }
  // scalastyle:on method.length

  // scalastyle:off method.length
  def prepareInboundWebSocket(
      fullProducerId: FullProducerId,
      args: InSocketArgs
  )(terminateProducer: () => Future[Done])(
      implicit cfg: AppCfg,
      as: ActorSystem,
      mat: Materializer,
      ec: ExecutionContext,
      maybeOpenIdClient: Option[OpenIdClient],
      jmxManager: Option[JmxManager]
  ): Route = {
    val topicStr = args.topic.value
    val keyTpe   = args.keyType.getOrElse(Formats.NoType)
    val valTpe   = args.valType

    implicit val keySer = keyTpe.serializer
    implicit val valSer = valTpe.serializer
    implicit val keyDec = keyTpe.decoder
    implicit val valDec = valTpe.decoder

    implicit val statsActorRef = prepareJmx(fullProducerId)

    // Init  monitoring flows
    val (jmxInFlow, jmxOutFlow) = jmxManager
      .map(_.producerStatsWireTaps(statsActorRef))
      .getOrElse((Flow[Message], Flow[Message]))

    val kafkaFlow = args.socketPayload match {
      case JsonPayload =>
        WsProducer
          .produceJson[keyTpe.Aux, valTpe.Aux](args)
          .map(_.asJson.printWith(noSpaces))
          .map[Message](TextMessage.apply)

      case AvroPayload =>
        WsProducer
          .produceAvro[keyTpe.Aux, valTpe.Aux](args)
          .map(_.toAvro)
          .map(avro => avroProducerResultSerde.serialize(topicStr, avro))
          .map(ByteString.fromArray)
          .map[Message](BinaryMessage.apply)
    }

    val jwtValidationFlow =
      JwtValidationTickerFlow.flow[Message](args.producerId, args.bearerToken)

    handleWebSocketMessages {
      val flow = jwtValidationFlow via jmxInFlow via kafkaFlow via jmxOutFlow
      flow.watchTermination() { (_, f) =>
        for {
          done <- f
          _    <- terminateProducer()
        } yield {
          jmxManager.foreach(_.removeProducerConnection())
          log.debug(
            "Inbound WebSocket connection with clientId " +
              s"${args.producerId.value} for topic ${args.topic.value} is " +
              "terminated."
          )
          done
        }
      }
    }
  }
  // scalastyle:on method.length
}
