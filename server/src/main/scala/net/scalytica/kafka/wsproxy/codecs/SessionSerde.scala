package net.scalytica.kafka.wsproxy.codecs

import io.circe.parser.parse
import io.circe.syntax._
import net.scalytica.kafka.wsproxy.codecs.Implicits.{
  oldSessionDecoder,
  sessionDecoder,
  sessionEncoder
}
import net.scalytica.kafka.wsproxy.errors.InvalidSessionStateFormat
import net.scalytica.kafka.wsproxy.logging.WithProxyLogger
import net.scalytica.kafka.wsproxy.session.{
  ConsumerInstance,
  ConsumerSession,
  OldSession,
  Session,
  SessionId
}

class SessionSerde extends StringBasedSerde[Session] with WithProxyLogger {

  override def serialize(topic: String, data: Session) =
    Option(data).map(d => ser.serialize(topic, d.asJson.noSpaces)).orNull

  override def deserialize(topic: String, data: Array[Byte]) = {
    Option(data).map { d =>
      val str = des.deserialize(topic, d)

      logger.trace(s"Deserialized session message from topic $topic to:\n$str")

      parse(str).flatMap(_.as[Session]) match {
        case Left(err) =>
          logger.warn(
            "Session data could not be deserialized to latest format," +
              "falling back to old format"
          )
          parse(str).flatMap(_.as[OldSession]) match {
            case Left(oldErr) =>
              logger.error(
                s"Exception deserializing session from topic $topic:" +
                  s"\n$str" +
                  s"\nfirst error: ${err.getMessage}" +
                  s"\nsecond error: ${oldErr.getMessage}"
              )
              throw InvalidSessionStateFormat(
                s"Exception deserializing session data from topic $topic"
              )

            case Right(old) =>
              ConsumerSession(
                sessionId = SessionId(old.consumerGroupId),
                groupId = old.consumerGroupId,
                maxConnections = old.consumerLimit,
                instances = old.consumers.map { o =>
                  ConsumerInstance(o.id, old.consumerGroupId, o.serverId)
                }
              )
          }

        case Right(session) => session
      }
    }.orNull
  }

}
