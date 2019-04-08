package net.scalytica.kafka.wsproxy

import akka.actor.ActorSystem
import akka.http.scaladsl.model.ws.TextMessage
import akka.http.scaladsl.server.Directives.handleWebSocketMessages
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer
import com.typesafe.scalalogging.Logger
import io.circe.Printer.noSpaces
import io.circe.syntax._
import net.scalytica.kafka.wsproxy.Configuration.AppCfg
import net.scalytica.kafka.wsproxy.codecs.Encoders._
import net.scalytica.kafka.wsproxy.models.{Formats, InSocketArgs}
import net.scalytica.kafka.wsproxy.producer.WsProducer

trait InboundWebSocket {

  private[this] val logger = Logger(getClass)

  /**
   * Request handler for the inbound Kafka WebSocket connection, with a Kafka
   * producer as the Sink.
   *
   * @param args the input arguments to pass on to the producer.
   * @return a [[Route]] for accessing the inbound WebSocket functionality.
   * @see [[WsProducer.produce]]
   */
  def inboundWebSocket(
      args: InSocketArgs
  )(
      implicit
      cfg: AppCfg,
      as: ActorSystem,
      mat: ActorMaterializer
  ): Route = handleWebSocketMessages {
    logger.debug("Initialising inbound websocket")

    val ktpe = args.keyType.getOrElse(Formats.NoType)

    implicit val keySer = ktpe.serializer
    implicit val valSer = args.valType.serializer
    implicit val keyDec = ktpe.decoder
    implicit val valDec = args.valType.decoder

    WsProducer
      .produce[ktpe.Aux, args.valType.Aux](args)
      .map(res => TextMessage.Strict(res.asJson.pretty(noSpaces)))
  }

}
