package net.scalytica.kafka.wsproxy.session

import net.scalytica.kafka.wsproxy.models.{WsClientId, WsGroupId}

case class SessionId(value: String)

object SessionId {
  def apply(clientId: WsClientId): SessionId = SessionId(clientId.value)
  def apply(groupId: WsGroupId): SessionId   = SessionId(groupId.value)

  def forProducer(mgid: Option[SessionId])(or: WsClientId): SessionId = {
    mgid.getOrElse(SessionId(s"${or.value}-producer-session"))
  }

  def forConsumer(mgid: Option[WsGroupId])(or: WsClientId): SessionId = {
    mgid
      .map(SessionId.apply)
      .getOrElse(SessionId(s"${or.value}-consumer-session"))
  }

//  implicit def groupIdToSessionId(groupId: WsGroupId): SessionId =
//    apply(groupId)
//
//  implicit def clientIdToSessionId(clientId: WsClientId): SessionId =
//    apply(clientId)
}
