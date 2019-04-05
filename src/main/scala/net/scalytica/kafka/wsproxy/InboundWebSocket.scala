package net.scalytica.kafka.wsproxy

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.http.scaladsl.server.Directives.handleWebSocketMessages
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Flow
import com.typesafe.scalalogging.Logger
import io.circe.syntax._
import io.circe.Printer.noSpaces
import net.scalytica.kafka.wsproxy.Configuration.AppConfig
import net.scalytica.kafka.wsproxy.codecs.Encoders._
import net.scalytica.kafka.wsproxy.models.{
  Formats,
  InSocketArgs,
  WsProducerResult
}
import net.scalytica.kafka.wsproxy.producer.WsProducer

trait InboundWebSocket {

  private[this] val logger = Logger(getClass)

  /**
   * Request handler for the inbound Kafka WebSocket connection.
   *
   * @param args the input arguments to pass on to the producer.
   * @return a [[Route]] for accessing the inbound WebSocket functionality.
   */
  def inboundWebSocket(
      args: InSocketArgs
  )(
      implicit
      cfg: AppConfig,
      as: ActorSystem,
      mat: ActorMaterializer
  ): Route = handleWebSocketMessages {
    logger.debug("Initialising inbound websocket")

    kafkaSink(args).map(res => TextMessage.Strict(res.asJson.pretty(noSpaces)))
  }

  /**
   * Creates a WebSocket Sink for the inbound channel.
   *
   * @param args the input arguments to pass on to the producer.
   * @return a [[WsProducer.produce]] for the provided args.
   */
  private[this] def kafkaSink(
      args: InSocketArgs
  )(
      implicit
      cfg: AppConfig,
      as: ActorSystem,
      mat: ActorMaterializer
  ): Flow[Message, WsProducerResult, NotUsed] = {
    val ktpe = args.keyType.getOrElse(Formats.NoType)

    implicit val keySer = ktpe.serializer
    implicit val valSer = args.valType.serializer
    implicit val keyDec = ktpe.decoder
    implicit val valDec = args.valType.decoder

    WsProducer.produce[ktpe.Aux, args.valType.Aux](args)
  }

}
