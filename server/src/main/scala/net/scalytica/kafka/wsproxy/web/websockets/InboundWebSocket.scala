package net.scalytica.kafka.wsproxy.web.websockets

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration._

import net.scalytica.kafka.wsproxy._
import net.scalytica.kafka.wsproxy.auth.JwtValidationTickerFlow
import net.scalytica.kafka.wsproxy.auth.OpenIdClient
import net.scalytica.kafka.wsproxy.codecs.Encoders._
import net.scalytica.kafka.wsproxy.config.Configuration.AppCfg
import net.scalytica.kafka.wsproxy.config.ReadableDynamicConfigHandlerRef
import net.scalytica.kafka.wsproxy.errors.RequestValidationError
import net.scalytica.kafka.wsproxy.errors.UnexpectedError
import net.scalytica.kafka.wsproxy.jmx.JmxManager
import net.scalytica.kafka.wsproxy.jmx.mbeans.ProducerClientStatsProtocol._
import net.scalytica.kafka.wsproxy.logging.WithProxyLogger
import net.scalytica.kafka.wsproxy.models._
import net.scalytica.kafka.wsproxy.producer.WsProducer
import net.scalytica.kafka.wsproxy.session.SessionHandlerImplicits._
import net.scalytica.kafka.wsproxy.session._
import net.scalytica.kafka.wsproxy.web.SocketProtocol.JsonPayload

import io.circe.Decoder
import io.circe.Printer.noSpaces
import io.circe.syntax._
import org.apache.kafka.common.serialization.Serializer
import org.apache.pekko.Done
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.typed
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Scheduler
import org.apache.pekko.actor.typed.scaladsl.adapter._
import org.apache.pekko.http.scaladsl.model.ws.Message
import org.apache.pekko.http.scaladsl.model.ws.TextMessage
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Flow
import org.apache.pekko.util.Timeout

trait InboundWebSocket extends ClientSpecificCfgLoader with WithProxyLogger {

  implicit private[this] val timeout: Timeout = 10 seconds

  private[this] def initSessionForProducer(
      serverId: WsServerId,
      fullProducerId: FullProducerId,
      maxConnections: Int,
      sh: ActorRef[SessionHandlerProtocol.SessionProtocol]
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
      implicit val tas: typed.ActorSystem[_] = as.toTyped
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
  def inboundWebSocket(
      args: InSocketArgs
  )(
      implicit cfg: AppCfg,
      maybeOpenIdClient: Option[OpenIdClient],
      maybeDynamicCfgHandlerRef: Option[ReadableDynamicConfigHandlerRef],
      as: ActorSystem,
      mat: Materializer,
      ec: ExecutionContext,
      sessionHandler: ActorRef[SessionHandlerProtocol.SessionProtocol],
      jmxManager: Option[JmxManager]
  ): Route = {
    log.debug(
      s"Initialising inbound WebSocket for topic ${args.topic.value}" +
        s" with payload ${args.socketPayload}"
    )
    inboundWebSocketRoute(args)(cfg)
  }

  // scalastyle:off method.length
  private[this] def inboundWebSocketRoute(
      args: InSocketArgs
  )(
      appCfg: AppCfg
  )(
      implicit maybeOpenIdClient: Option[OpenIdClient],
      maybeDynamicCfgHandlerRef: Option[ReadableDynamicConfigHandlerRef],
      as: ActorSystem,
      mat: Materializer,
      ec: ExecutionContext,
      sessionHandler: ActorRef[SessionHandlerProtocol.SessionProtocol],
      jmxManager: Option[JmxManager]
  ): Route = {
    implicit val scheduler: Scheduler = as.toTyped.scheduler
    implicit val cfg: AppCfg          = applyDynamicConfigs(args)(appCfg)

    val serverId       = cfg.server.serverId
    val producerId     = args.producerId
    val sessionId      = SessionId(producerId)
    val fullProducerId = FullProducerId(producerId, args.instanceId)
    val prodLimitCfg   = cfg.producer.limits.forProducer(producerId)
    val maxCons = {
      if (args.transactional) 1
      else {
        prodLimitCfg
          .flatMap(_.maxConnections)
          .getOrElse(cfg.producer.limits.defaultMaxConnectionsPerClient)
      }
    }

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

      case InstanceLimitReached(_) =>
        throw RequestValidationError(
          s"The max number of WebSockets for session ${sessionId.value} " +
            s"has been reached. Limit is $maxCons."
        )

      case SessionNotFound(_) =>
        throw RequestValidationError(
          s"Could not find an active session for ${sessionId.value}."
        )

      case wrong =>
        log.error(
          "Adding producer failed with an unexpected state." +
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
  private[this] def prepareInboundWebSocket(
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
    val topicStr   = args.topic.value
    val producerId = args.producerId
    val keyTpe     = args.keyType.getOrElse(Formats.NoType)
    val valTpe     = args.valType

    implicit val keySer: Serializer[keyTpe.Aux] = keyTpe.serializer
    implicit val valSer: Serializer[valTpe.Aux] = valTpe.serializer
    implicit val keyDec: Decoder[keyTpe.Aux]    = keyTpe.decoder
    implicit val valDec: Decoder[valTpe.Aux]    = valTpe.decoder

    implicit val statsActorRef: ActorRef[ProducerClientStatsCommand] =
      prepareJmx(fullProducerId)

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
    }

    val jwtValidationFlow =
      JwtValidationTickerFlow.flow[Message](producerId, args.bearerToken)

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
              s"${producerId.value} for topic $topicStr is terminated."
          )
          done
        }
      }
    }
  }
  // scalastyle:on method.length
}
