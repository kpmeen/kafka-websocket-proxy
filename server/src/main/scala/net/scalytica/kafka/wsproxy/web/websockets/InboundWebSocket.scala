package net.scalytica.kafka.wsproxy.web.websockets

import akka.actor.ActorSystem
import akka.http.scaladsl.model.ws.{BinaryMessage, TextMessage}
import akka.http.scaladsl.server.Directives.handleWebSocketMessages
import akka.http.scaladsl.server.Route
import akka.stream.Materializer
import akka.util.ByteString
import io.circe.Printer.noSpaces
import io.circe.syntax._
import net.scalytica.kafka.wsproxy.config.Configuration.AppCfg
import net.scalytica.kafka.wsproxy.web.SocketProtocol.{AvroPayload, JsonPayload}
import net.scalytica.kafka.wsproxy.codecs.Encoders._
import net.scalytica.kafka.wsproxy.codecs.ProtocolSerdes.{
  avroProducerRecordSerde,
  avroProducerResultSerde
}
import net.scalytica.kafka.wsproxy.logging.WithProxyLogger
import net.scalytica.kafka.wsproxy.models.{Formats, InSocketArgs}
import net.scalytica.kafka.wsproxy.producer.WsProducer

trait InboundWebSocket extends WithProxyLogger {

  /**
   * Request handler for the inbound Kafka WebSocket connection, with a Kafka
   * producer as the Sink.
   *
   * @param args
   *   the input arguments to pass on to the producer.
   * @param as
   *   Implicitly provided [[ActorSystem]]
   * @param mat
   *   Implicitly provided [[Materializer]]
   * @return
   *   a [[Route]] for accessing the inbound WebSocket functionality.
   * @see
   *   [[WsProducer.produceJson]]
   */
  def inboundWebSocket(
      args: InSocketArgs
  )(implicit cfg: AppCfg, as: ActorSystem, mat: Materializer): Route =
    handleWebSocketMessages {
      logger.debug(
        s"Initialising inbound websocket for topic ${args.topic.value}" +
          s" with payload ${args.socketPayload}"
      )

      val ktpe = args.keyType.getOrElse(Formats.NoType)

      implicit val keySer = ktpe.serializer
      implicit val valSer = args.valType.serializer
      implicit val keyDec = ktpe.decoder
      implicit val valDec = args.valType.decoder

      args.socketPayload match {
        case JsonPayload =>
          WsProducer
            .produceJson[ktpe.Aux, args.valType.Aux](args)
            .map(res => TextMessage.Strict(res.asJson.printWith(noSpaces)))

        case AvroPayload =>
          WsProducer.produceAvro[ktpe.Aux, args.valType.Aux](args).map { res =>
            val bs = ByteString.fromArray(
              avroProducerResultSerde.serialize(args.topic.value, res.toAvro)
            )
            BinaryMessage.Strict(bs)
          }
      }
    }

}
