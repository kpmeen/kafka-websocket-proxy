package net.scalytica.kafka.wsproxy.models

import java.nio.charset.StandardCharsets

import net.scalytica.kafka.wsproxy.avro.SchemaTypes.KafkaMessageHeader
import org.apache.kafka.common.header.Headers

import scala.collection.JavaConverters._

case class KafkaHeader(key: String, value: String)

object KafkaHeader {

  def fromAvro(h: KafkaMessageHeader): KafkaHeader =
    KafkaHeader(h.key, h.value)

  def fromKafkaRecordHeaders(headers: Headers): Option[Seq[KafkaHeader]] = {
    val sheaders = headers.iterator().asScala.toSeq

    if (sheaders.isEmpty) None
    else
      Option(sheaders.map { h =>
        val value = new String(h.value, StandardCharsets.UTF_8)
        KafkaHeader(h.key, value)
      })
  }

}
