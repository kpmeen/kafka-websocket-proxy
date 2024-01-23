package net.scalytica.kafka.wsproxy.codecs

import net.scalytica.kafka.wsproxy.session.SessionId

class SessionIdSerde extends StringBasedSerde[SessionId] {

  override def serialize(topic: String, data: SessionId): Array[Byte] =
    Option(data).map(d => ser.serialize(topic, d.value)).orNull

  override def deserialize(topic: String, data: Array[Byte]): SessionId = {
    Option(data).flatMap { d =>
      val str = des.deserialize(topic, d)
      Option(str).map(SessionId.apply)
    }.orNull
  }

}
