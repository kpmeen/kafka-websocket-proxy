package net.scalytica.kafka.wsproxy.producer

import net.scalytica.kafka.wsproxy.logging.WithProxyLogger
import net.scalytica.kafka.wsproxy.models.ProducerEmptyMessage
import net.scalytica.kafka.wsproxy.models.WsProducerRecord

import io.circe.Decoder

trait InputJsonParser { self: WithProxyLogger =>

  /**
   * Parses an input message, in the form of a JSON String, into an instance of
   * [[WsProducerRecord]], which will be passed on to Kafka down-stream.
   *
   * @param jsonStr
   *   the String containing the JSON formatted message
   * @param keyDec
   *   the JSON decoder to use for the message key
   * @param valDec
   *   the JSON decoder to use for the message value
   * @tparam K
   *   the message key type
   * @tparam V
   *   the message value type
   * @return
   *   an instance of [[WsProducerRecord]]
   */
  @throws[Throwable]
  protected def parseInput[K, V](
      jsonStr: String
  )(implicit keyDec: Decoder[K], valDec: Decoder[V]): WsProducerRecord[K, V] = {
    import io.circe._
    import io.circe.parser._
    import net.scalytica.kafka.wsproxy.codecs.Decoders._

    if (jsonStr.isEmpty) ProducerEmptyMessage
    else {
      parse(jsonStr) match {
        case Left(ParsingFailure(message, err)) =>
          log.error(s"Error parsing JSON string:\n$message")
          log.debug(s"JSON was: $jsonStr")
          throw err

        case Right(json) =>
          json.as[WsProducerRecord[K, V]] match {
            case Left(err)  => throw err
            case Right(wpr) => wpr
          }
      }
    }
  }

}
