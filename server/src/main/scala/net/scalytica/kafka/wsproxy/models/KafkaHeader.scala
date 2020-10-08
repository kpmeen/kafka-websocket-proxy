package net.scalytica.kafka.wsproxy.models

import java.nio.charset.StandardCharsets

import net.scalytica.kafka.wsproxy.avro.SchemaTypes.KafkaMessageHeader
import org.apache.kafka.common.header.Headers
import org.apache.kafka.common.header.internals.RecordHeader

import scala.collection.JavaConverters._

case class KafkaHeader(key: String, value: String) {

  def asRecordHeader: RecordHeader = {
    new RecordHeader(key, value.getBytes(StandardCharsets.UTF_8))
  }

}

object KafkaHeader {

  def fromAvro(h: KafkaMessageHeader): KafkaHeader = KafkaHeader(h.key, h.value)

  def fromKafkaRecordHeaders(headers: Headers): Option[Seq[KafkaHeader]] =
    Option(headers).map(_.iterator().asScala.toSeq).flatMap {
      case theHeaders if theHeaders.isEmpty => None
      case theHeaders =>
        Option(theHeaders.map { h =>
          KafkaHeader(h.key, new String(h.value, StandardCharsets.UTF_8))
        })
    }

}
