package net.scalytica.kafka.wsproxy.auth

import net.scalytica.kafka.wsproxy.models.AclCredentials
import org.apache.kafka.clients.admin.ScramMechanism
import org.apache.kafka.common.security.plain.internals.PlainSaslServer
import org.scalatest.OptionValues
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec

class KafkaLoginModulesSpec
    extends AnyWordSpec
    with Matchers
    with OptionValues {

  val DummyCredentials = AclCredentials("foo", "bar")

  val PlainLoginModuleString =
    "org.apache.kafka.common.security.plain.PlainLoginModule"

  val ScramLoginModuleString =
    "org.apache.kafka.common.security.scram.ScramLoginModule"

  "The KafkaLoginModules" should {

    "return correct login module for sasl.mechanism PLAIN auth" in {
      val res = KafkaLoginModules.fromSaslMechanism(
        maybeSaslMechanism = Option(PlainSaslServer.PLAIN_MECHANISM),
        maybeAclCredentials = Option(DummyCredentials)
      )
      res.value.moduleClassName mustBe PlainLoginModuleString
    }

    "return correct login module for sasl.mechanism SCRAM-SHA-512 auth" in {
      val res = KafkaLoginModules.fromSaslMechanism(
        maybeSaslMechanism =
          Option(ScramMechanism.SCRAM_SHA_512.mechanismName()),
        maybeAclCredentials = Option(DummyCredentials)
      )
      res.value.moduleClassName mustBe ScramLoginModuleString
    }

    "return correct login module for sasl.mechanism SCRAM-SHA-256 auth" in {
      val res = KafkaLoginModules.fromSaslMechanism(
        maybeSaslMechanism =
          Option(ScramMechanism.SCRAM_SHA_256.mechanismName()),
        maybeAclCredentials = Option(DummyCredentials)
      )
      res.value.moduleClassName mustBe ScramLoginModuleString
    }

    "return no login module for unsupported sasl.mechanism" in {
      KafkaLoginModules.fromSaslMechanism(
        maybeSaslMechanism = Option("SCRAM-SHA-258"),
        maybeAclCredentials = Option(DummyCredentials)
      ) mustBe None

      KafkaLoginModules.fromSaslMechanism(
        maybeSaslMechanism = Option("OAUTHBEARER"),
        maybeAclCredentials = Option(DummyCredentials)
      ) mustBe None

      KafkaLoginModules.fromSaslMechanism(
        maybeSaslMechanism = Option("GSSAPI"),
        maybeAclCredentials = Option(DummyCredentials)
      ) mustBe None

      KafkaLoginModules.fromSaslMechanism(
        maybeSaslMechanism = Option("LDAP"),
        maybeAclCredentials = Option(DummyCredentials)
      ) mustBe None
    }

  }

}
