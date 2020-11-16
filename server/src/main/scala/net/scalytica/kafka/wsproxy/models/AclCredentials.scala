package net.scalytica.kafka.wsproxy.models

import net.scalytica.kafka.wsproxy.{PlainLogin, SaslJaasConfig}

case class AclCredentials(username: String, password: String)

object AclCredentials {

  def buildSaslJaasProps(
      maybeAclCredentials: Option[AclCredentials]
  ): Map[String, String] = {
    maybeAclCredentials match {
      case Some(c) =>
        Map(SaslJaasConfig -> PlainLogin(c.username, c.password))

      case None =>
        Map(SaslJaasConfig -> "")
    }
  }

}
