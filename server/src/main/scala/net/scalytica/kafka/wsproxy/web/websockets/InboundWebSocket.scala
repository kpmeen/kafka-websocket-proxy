package net.scalytica.kafka.wsproxy.web.websockets

import akka.Done
import akka.actor.ActorSystem
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.{ActorRef, Scheduler}
import akka.http.scaladsl.model.ws.{BinaryMessage, Message, TextMessage}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{Route, ValidationRejection}
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
import net.scalytica.kafka.wsproxy.jmx.JmxManager
import net.scalytica.kafka.wsproxy.jmx.mbeans.ProducerClientStatsProtocol._
import net.scalytica.kafka.wsproxy.logging.WithProxyLogger
import net.scalytica.kafka.wsproxy.models.{
  Formats,
  InSocketArgs,
  WsClientId,
  WsServerId
}
import net.scalytica.kafka.wsproxy.session.SessionHandler._
import net.scalytica.kafka.wsproxy.session.{
  InstanceAdded,
  InstanceExists,
  InstanceLimitReached,
  SessionHandlerProtocol,
  SessionNotFound
}
import net.scalytica.kafka.wsproxy.producer.WsProducer
import net.scalytica.kafka.wsproxy.session.SessionId
import net.scalytica.kafka.wsproxy.web.SocketProtocol.{AvroPayload, JsonPayload}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

trait InboundWebSocket extends WithProxyLogger {

  implicit private[this] val timeout: Timeout = 10 seconds

  private[this] def initSessionForProducer(
      serverId: WsServerId,
      sessionId: SessionId,
      clientId: WsClientId,
      maxConnections: Int,
      sh: ActorRef[SessionHandlerProtocol.Protocol]
  )(
      implicit ec: ExecutionContext,
      scheduler: Scheduler
  ) = {
    for {
      ir     <- sh.initProducerSession(sessionId, maxConnections)
      _      <- logger.debugf(s"Session ${ir.session.sessionId} ready")
      addRes <- sh.addProducer(sessionId, clientId, serverId)
    } yield addRes
  }

  private[this] def prepareJmx(clientId: WsClientId)(
      implicit jmx: Option[JmxManager],
      as: ActorSystem
  ): ActorRef[ProducerClientStatsCommand] = jmx
    .map { j =>
      j.addProducerConnection()
      j.initProducerClientStatsActor(clientId)
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
    logger.debug(
      s"Initialising inbound WebSocket for topic ${args.topic.value}" +
        s" with payload ${args.socketPayload}"
    )
    implicit val scheduler = as.scheduler.toTyped

    val serverId     = cfg.server.serverId
    val clientId     = args.clientId
    val sessionId    = SessionId(clientId)
    val prodLimitCfg = cfg.producer.limits.forProducer(clientId)
    val maxCons = prodLimitCfg
      .flatMap(_.maxConnections)
      .getOrElse(cfg.producer.limits.defaultMaxConnectionsPerClient)

    val producerAddResult = initSessionForProducer(
      serverId = serverId,
      sessionId = sessionId,
      clientId = clientId,
      maxConnections = maxCons,
      sh = sessionHandler
    )

    onSuccess(producerAddResult) {
      case InstanceAdded(_) =>
        prepareInboundWebSocket(args) { () =>
          jmxManager.foreach(_.removeProducerConnection())
          // Remove the producer from the session handler
          sessionHandler
            .removeProducer(sessionId, clientId, serverId)
            .map(_ => Done)
            .recoverWith { case t: Throwable =>
              logger.trace("Producer removal failed due to an error", t)
              Future.successful(Done)
            }
        }

      case InstanceExists(_) =>
        reject(
          ValidationRejection(
            s"WebSocket for producer ${clientId.value} in session " +
              s"${sessionId.value} not established because a consumer with" +
              " the same ID is already registered."
          )
        )

      case InstanceLimitReached(s) =>
        reject(
          ValidationRejection(
            s"The max number of WebSockets for session ${sessionId.value} " +
              s"has been reached. Limit is ${s.maxConnections}"
          )
        )

      case SessionNotFound(_) =>
        reject(
          ValidationRejection(
            s"Could not find an active session for ${sessionId.value}."
          )
        )

      case wrong =>
        logger.error(
          s"Adding producer failed with an unexpected state." +
            s" Session:\n ${wrong.session}"
        )
        failWith(
          new InternalError(
            "An unexpected error occurred when trying to establish the " +
              s"WebSocket producer ${clientId.value} in session" +
              s" ${sessionId.value}."
          )
        )
    }
  }
  // scalastyle:on method.length

  // scalastyle:off method.length
  def prepareInboundWebSocket(
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

    implicit val statsActorRef = prepareJmx(args.clientId)

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
      JwtValidationTickerFlow.flow[Message](args.clientId, args.bearerToken)

    handleWebSocketMessages {
      val flow = jwtValidationFlow via jmxInFlow via kafkaFlow via jmxOutFlow
      flow.watchTermination() { (_, f) =>
        for {
          done <- f
          _    <- terminateProducer()
        } yield {
          jmxManager.foreach(_.removeProducerConnection())
          logger.debug(
            "Inbound WebSocket connection with clientId " +
              s"${args.clientId.value} for topic ${args.topic.value} is " +
              "terminated."
          )
          done
        }
      }
    }
  }
  // scalastyle:on method.length
}
