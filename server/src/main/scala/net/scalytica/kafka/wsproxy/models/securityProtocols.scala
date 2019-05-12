package net.scalytica.kafka.wsproxy.models

import org.apache.kafka.common.security.auth.{
  SecurityProtocol => KSecurityProtocol
}

sealed abstract class SecurityProtocol {

  def stringValue: String

}

object SecurityProtocol {

  def fromString(str: String): SecurityProtocol = {
    fromJava(KSecurityProtocol.forName(str.toUpperCase))
  }

  def fromJava(ksec: KSecurityProtocol): SecurityProtocol = {
    ksec match {
      case KSecurityProtocol.PLAINTEXT      => Plaintext
      case KSecurityProtocol.SSL            => Ssl
      case KSecurityProtocol.SASL_PLAINTEXT => SaslPlaintext
      case KSecurityProtocol.SASL_SSL       => SaslSsl
    }
  }

}

case object Plaintext extends SecurityProtocol {
  override def stringValue = KSecurityProtocol.PLAINTEXT.name
}
case object Ssl extends SecurityProtocol {
  override def stringValue = KSecurityProtocol.SSL.name
}
case object SaslPlaintext extends SecurityProtocol {
  override def stringValue = KSecurityProtocol.SASL_PLAINTEXT.name
}
case object SaslSsl extends SecurityProtocol {
  override def stringValue = KSecurityProtocol.SASL_SSL.name
}
