package net.scalytica.kafka.wsproxy.producer

import akka.NotUsed
import akka.http.scaladsl.model.ws.Message
import akka.stream.scaladsl.Flow
import akka.stream.{Materializer, OverflowStrategy}
import akka.util.ByteString
import io.circe.Decoder
import net.scalytica.kafka.wsproxy.avro.SchemaTypes.AvroProducerRecord
import net.scalytica.kafka.wsproxy.codecs.WsProxyAvroSerde
import net.scalytica.kafka.wsproxy.config.Configuration.{
  AppCfg,
  ClientSpecificLimitCfg
}
import net.scalytica.kafka.wsproxy.logging.WithProxyLogger
import net.scalytica.kafka.wsproxy.models.Formats.FormatType
import net.scalytica.kafka.wsproxy.models.{
  InSocketArgs,
  ProducerEmptyMessage,
  WsClientId,
  WsProducerRecord
}
import net.scalytica.kafka.wsproxy.streams.ProxyFlowExtras

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

private[producer] trait ProducerFlowExtras
    extends ProxyFlowExtras
    with InputJsonParser {
  self: WithProxyLogger =>

  /** Convenience function for logging and throwing an error in a Flow */
  def logAndEmpty[T](msg: String, t: Throwable)(empty: T): T = {
    logger.error(msg, t)
    empty
  }

  def rateLimitedJsonToWsProducerRecordFlow[K, V](
      args: InSocketArgs
  )(
      implicit cfg: AppCfg,
      kd: Decoder[K],
      vd: Decoder[V],
      ec: ExecutionContext,
      mat: Materializer
  ): Flow[Message, WsProducerRecord[K, V], NotUsed] =
    (rateLimiter(args) via wsMessageToStringFlow)
      .recover { case t: Exception =>
        logAndEmpty("There was an error processing a JSON message", t)("")
      }
      .map(str => parseInput[K, V](str))
      .recover { case t: Exception =>
        logAndEmpty(s"JSON message could not be parsed", t)(
          ProducerEmptyMessage
        )
      }
      .filter(_.nonEmpty)

  def rateLimitedAvroToWsProducerRecordFlow[K, V](
      args: InSocketArgs,
      keyType: FormatType,
      valType: FormatType
  )(
      implicit cfg: AppCfg,
      serde: WsProxyAvroSerde[AvroProducerRecord],
      ec: ExecutionContext,
      mat: Materializer
  ): Flow[Message, WsProducerRecord[K, V], NotUsed] =
    (rateLimiter(args) via wsMessageToByteStringFlow)
      .recover { case t: Exception =>
        logAndEmpty("There was an error processing an Avro message", t)(
          ByteString.empty
        )
      }
      .log("avroProducerFlow", m => s"Trying to deserialize bytes: $m")
      .map(bs => serde.deserialize(bs.toArray))
      .log("avroProducerFlow", m => s"Deserialized bytes into: $m")
      .recover { case t: Exception =>
        logAndEmpty(s"Avro message could not be deserialized", t)(
          AvroProducerRecord.Empty
        )
      }
      .filterNot(_.isEmpty)
      .map { apr =>
        WsProducerRecord.fromAvro[K, V](apr)(
          keyFormatType = keyType,
          valueFormatType = valType
        )
      }

  def rateLimiter(args: InSocketArgs)(
      implicit cfg: AppCfg
  ): Flow[Message, Message, NotUsed] = {
    val defaultMps = cfg.producer.limits.defaultMessagesPerSecond
    rateLimitFlow(
      args.clientId,
      defaultMps,
      cfg.producer.limits.clientSpecificLimits
    )
  }

  def rateLimitFlow(
      clientId: WsClientId,
      defaultMessagesPerSecond: Int,
      clientLimits: Seq[ClientSpecificLimitCfg]
  ): Flow[Message, Message, NotUsed] = {
    val mps = clientLimits
      .find(_.id.equals(clientId.value))
      .flatMap(_.messagesPerSecond)
      .getOrElse(defaultMessagesPerSecond)

    if (mps == 0) {
      Flow[Message].log("rateLimiterFlow", _ => "no rate limiting")
    } else {
      Flow[Message]
        .log("rateLimiterFlow", _ => s"Limiting to $mps messages per second")
        .buffer(mps, OverflowStrategy.backpressure)
        .throttle(mps, 1 second)
    }
  }

}

private[producer] object ProducerFlowExtras
    extends ProducerFlowExtras
    with WithProxyLogger
