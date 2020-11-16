package net.scalytica.kafka.wsproxy.web.websockets

import akka.actor.ActorSystem
import akka.http.scaladsl.model.ws.{BinaryMessage, Message, TextMessage}
import akka.http.scaladsl.server.Directives.handleWebSocketMessages
import akka.http.scaladsl.server.Route
import akka.stream.Materializer
import akka.util.ByteString
import io.circe.Printer.noSpaces
import io.circe.syntax._
import net.scalytica.kafka.wsproxy.codecs.Encoders._
import net.scalytica.kafka.wsproxy.codecs.ProtocolSerdes.{
  avroProducerRecordSerde,
  avroProducerResultSerde
}
import net.scalytica.kafka.wsproxy.config.Configuration.AppCfg
import net.scalytica.kafka.wsproxy.jmx.JmxManager
import net.scalytica.kafka.wsproxy.logging.WithProxyLogger
import net.scalytica.kafka.wsproxy.models.{Formats, InSocketArgs}
import net.scalytica.kafka.wsproxy.producer.WsProducer
import net.scalytica.kafka.wsproxy.web.SocketProtocol.{AvroPayload, JsonPayload}

trait InboundWebSocket extends WithProxyLogger {

  // scalastyle:off method.length
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
   * @param jmx
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
      as: ActorSystem,
      mat: Materializer,
      jmx: Option[JmxManager]
  ): Route = {
    handleWebSocketMessages {
      logger.debug(
        s"Initialising inbound websocket for topic ${args.topic.value}" +
          s" with payload ${args.socketPayload}"
      )
      implicit val ec = as.dispatcher

      val topicStr = args.topic.value
      val ktpe     = args.keyType.getOrElse(Formats.NoType)

      implicit val keySer = ktpe.serializer
      implicit val valSer = args.valType.serializer
      implicit val keyDec = ktpe.decoder
      implicit val valDec = args.valType.decoder

      // Update the number of active producer connections
      jmx.foreach(j => j.addProducerConnection())

      val socketFlow = args.socketPayload match {
        case JsonPayload =>
          WsProducer
            .produceJson[ktpe.Aux, args.valType.Aux](args)
            .map(_.asJson.printWith(noSpaces))
            .map[Message](TextMessage.apply)

        case AvroPayload =>
          WsProducer
            .produceAvro[ktpe.Aux, args.valType.Aux](args)
            .map(_.toAvro)
            .map(avro => avroProducerResultSerde.serialize(topicStr, avro))
            .map(ByteString.fromArray)
            .map[Message](BinaryMessage.apply)
      }

      socketFlow.watchTermination() { (_, f) =>
        f.foreach { _ =>
          jmx.foreach(_.removeProducerConnection())
          logger.debug(
            "Inbound WebSocket connection with clientId " +
              s"${args.clientId.value} for topic ${args.topic.value} is " +
              "terminated."
          )
        }
      }
    }
  }
  // scalastyle:on method.length

}
