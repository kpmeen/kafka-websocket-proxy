package net.scalytica.kafka.wsproxy.session

import net.scalytica.kafka.wsproxy.models.WsClientId
import net.scalytica.kafka.wsproxy.models.WsGroupId
import net.scalytica.kafka.wsproxy.models.WsProducerId

case class SessionId(value: String)

object SessionId {
  def apply(producerId: WsProducerId): SessionId = SessionId(producerId.value)
  def apply(groupId: WsGroupId): SessionId       = SessionId(groupId.value)

  def forProducer(mgid: Option[SessionId])(or: WsProducerId): SessionId = {
    mgid.getOrElse(SessionId(s"${or.value}-producer-session"))
  }

  def forConsumer(mgid: Option[WsGroupId])(or: WsClientId): SessionId = {
    mgid
      .map(SessionId.apply)
      .getOrElse(SessionId(s"${or.value}-consumer-session"))
  }

}
