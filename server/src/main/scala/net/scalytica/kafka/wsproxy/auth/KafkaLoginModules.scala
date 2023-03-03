package net.scalytica.kafka.wsproxy.auth

import net.scalytica.kafka.wsproxy.SaslJaasConfig
import net.scalytica.kafka.wsproxy.logging.WithProxyLogger
import net.scalytica.kafka.wsproxy.models.AclCredentials
import org.apache.kafka.clients.admin.ScramMechanism
import org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule
import org.apache.kafka.common.security.plain.PlainLoginModule
import org.apache.kafka.common.security.plain.internals.PlainSaslServer
import org.apache.kafka.common.security.scram.ScramLoginModule

object KafkaLoginModules extends WithProxyLogger {

  sealed trait LoginModuleParameters {
    def asJaasString: String
  }

  case class PlainParameters(uname: String, passw: String)
      extends LoginModuleParameters {

    override def asJaasString = s"""username="$uname" password="$passw""""

  }
  case class ScramParameters(uname: String, passw: String)
      extends LoginModuleParameters {

    override def asJaasString = s"""username="$uname" password="$passw""""

  }

  case class OAuthParameters(uname: String, passw: String)
      extends LoginModuleParameters {

    override def asJaasString = s"""username="$uname" password="$passw""""

  }

  sealed trait KafkaLoginModule {
    val loginParams: LoginModuleParameters
    val moduleClassName: String

    protected def jaasPrefix: String = s"$moduleClassName required"

    def buildJaasConfigValue: String

    def buildJaasProperty: Map[String, String] = {
      Map(SaslJaasConfig -> buildJaasConfigValue)
    }
  }

  case class Plain(loginParams: PlainParameters) extends KafkaLoginModule {
    override val moduleClassName = classOf[PlainLoginModule].getName

    override def buildJaasConfigValue = {
      s"""$jaasPrefix ${loginParams.asJaasString};"""
    }
  }

  case class Scram(loginParams: ScramParameters) extends KafkaLoginModule {
    override val moduleClassName = classOf[ScramLoginModule].getName

    override def buildJaasConfigValue = {
      s"""$jaasPrefix ${loginParams.asJaasString};"""
    }
  }

  object Scram {

    def validScram(m: String): Boolean = {
      val provided = ScramMechanism.fromMechanismName(m)
      ScramMechanism.SCRAM_SHA_256 == provided ||
      ScramMechanism.SCRAM_SHA_512 == provided
    }

  }

  case class OAuth(loginParams: OAuthParameters) extends KafkaLoginModule {
    override val moduleClassName = classOf[OAuthBearerLoginModule].getName

    override def buildJaasConfigValue =
      throw new NotImplementedError(
        s"Support for $moduleClassName is currently not supported"
      )
  }

  implicit private[this] def aclToPlainParams(
      ac: AclCredentials
  ): PlainParameters = PlainParameters(ac.username, ac.password)

  implicit private[this] def aclToScramParams(
      ac: AclCredentials
  ): ScramParameters = ScramParameters(ac.username, ac.password)

  def fromSaslMechanism(
      maybeSaslMechanism: Option[String],
      maybeAclCredentials: Option[AclCredentials]
  ): Option[KafkaLoginModule] = {
    maybeAclCredentials.flatMap { ac =>
      maybeSaslMechanism.flatMap {
        case PlainSaslServer.PLAIN_MECHANISM => Some(Plain(ac))
        case m if Scram.validScram(m)        => Some(Scram(ac))
        case m =>
          log.warn(
            s"Using sasl.mechanism $m is not yet supported. " +
              "Please use PLAIN, SCRAM-SHA-256 or SCRAM-SHA-512."
          )
          None
      }
    }
  }

  def buildJaasProperty(
      maybeLogin: Option[KafkaLoginModule]
  ): Map[String, String] = {
    maybeLogin.map(_.buildJaasProperty).getOrElse(Map(SaslJaasConfig -> ""))
  }

}
