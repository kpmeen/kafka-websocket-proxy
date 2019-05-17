package net.scalytica.kafka.wsproxy.codecs

import net.scalytica.kafka.wsproxy.models.WsGroupId

class WsGroupIdSerde extends StringBasedSerde[WsGroupId] {

  override def serialize(topic: String, data: WsGroupId) =
    Option(data).map(d => ser.serialize(topic, d.value)).orNull

  override def deserialize(topic: String, data: Array[Byte]): WsGroupId = {
    Option(data).flatMap { d =>
      val str = des.deserialize(topic, d)
      Option(str).map(WsGroupId.apply)
    }.orNull
  }

}
