package net.scalytica.kafka.wsproxy.codecs

import io.circe.parser.parse
import io.circe.syntax._
import io.circe.{Decoder, Encoder, Printer}
import net.scalytica.kafka.wsproxy.session.Session

class SessionSerde(implicit enc: Encoder[Session], dec: Decoder[Session])
    extends StringBasedSerde[Session] {

  override def serialize(topic: String, data: Session) =
    Option(data)
      .map(d => ser.serialize(topic, d.asJson.pretty(Printer.noSpaces)))
      .orNull

  override def deserialize(topic: String, data: Array[Byte]) = {
    Option(data).map { d =>
      val str = des.deserialize(topic, d)
      parse(str).flatMap(_.as[Session]) match {
        case Left(err)      => throw err
        case Right(session) => session
      }
    }.orNull
  }

}
