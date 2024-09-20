package net.scalytica.kafka.wsproxy.producer

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import net.scalytica.kafka.wsproxy.config.Configuration.AppCfg
import net.scalytica.kafka.wsproxy.config.Configuration.ClientSpecificLimitCfg
import net.scalytica.kafka.wsproxy.logging.WithProxyLogger
import net.scalytica.kafka.wsproxy.models.InSocketArgs
import net.scalytica.kafka.wsproxy.models.ProducerEmptyMessage
import net.scalytica.kafka.wsproxy.models.WsProducerId
import net.scalytica.kafka.wsproxy.models.WsProducerRecord
import net.scalytica.kafka.wsproxy.streams.ProxyFlowExtras

import io.circe.Decoder
import org.apache.pekko.NotUsed
import org.apache.pekko.http.scaladsl.model.ws.Message
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.OverflowStrategy
import org.apache.pekko.stream.scaladsl.Flow

private[producer] trait ProducerFlowExtras
    extends ProxyFlowExtras
    with InputJsonParser {
  self: WithProxyLogger =>

  /** Convenience function for logging and throwing an error in a Flow */
  def logAndEmpty[T](msg: String, t: Throwable)(empty: T): T = {
    log.error(msg, t)
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
        logAndEmpty("JSON message could not be parsed", t)(
          ProducerEmptyMessage
        )
      }
      .filter(_.nonEmpty)

  def rateLimiter(args: InSocketArgs)(
      implicit cfg: AppCfg
  ): Flow[Message, Message, NotUsed] = {
    val defaultMps = cfg.producer.limits.defaultMessagesPerSecond
    rateLimitFlow(
      args.producerId,
      defaultMps,
      cfg.producer.limits.clientSpecificLimits
    )
  }

  def rateLimitFlow(
      producerId: WsProducerId,
      defaultMessagesPerSecond: Int,
      clientLimits: Seq[ClientSpecificLimitCfg]
  ): Flow[Message, Message, NotUsed] = {
    val mps = clientLimits
      .find(_.id.equals(producerId.value))
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
