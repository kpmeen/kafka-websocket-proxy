package net.scalytica.kafka.wsproxy.auth

import java.security.KeyPairGenerator
import java.security.interfaces.{RSAPrivateKey, RSAPublicKey}
import java.util.UUID

import net.scalytica.kafka.wsproxy.errors.InvalidPublicKeyError
import org.scalatest.TryValues
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec
import pdi.jwt.{JwtAlgorithm, JwtBase64}

// scalastyle:off magic.number
class JwkSpec extends AnyWordSpec with Matchers with TryValues {

  private[this] def base64UrlEncode(bytes: Array[Byte]): String =
    JwtBase64.encodeString(bytes)

  def generateJwk(
      keyType: String = PubKeyAlgo
  ): (Jwk, RSAPublicKey, RSAPrivateKey) = {
    val generator = KeyPairGenerator.getInstance(PubKeyAlgo)
    generator.initialize(2048) // scalastyle:ignore
    val keyPair    = generator.generateKeyPair()
    val privateKey = keyPair.getPrivate.asInstanceOf[RSAPrivateKey]
    val publicKey  = keyPair.getPublic.asInstanceOf[RSAPublicKey]
    val keyId      = UUID.randomUUID().toString

    val modulus  = publicKey.getModulus
    val exponent = publicKey.getPublicExponent

    val jwk = Jwk(
      kty = keyType,
      kid = Option(keyId),
      alg = Option(JwtAlgorithm.RS256.name),
      use = Option("sig"),
      key_ops = None,
      x5u = None,
      x5c = None,
      x5t = None,
      n = Option(base64UrlEncode(modulus.toByteArray)),
      e = Option(base64UrlEncode(exponent.toByteArray))
    )

    (jwk, publicKey, privateKey)
  }

  val (goodJwk, rsa256PubKey, rsa256PrivKey) = generateJwk()

  val bad = generateJwk("HMAC")._1

  "Jwk object" should {

    "generate a public key from a Jwk instance" in {
      val res = Jwk.generatePublicKey(goodJwk).success.value
      res.getAlgorithm mustBe rsa256PubKey.getAlgorithm
      res.getFormat mustBe rsa256PubKey.getFormat
      res.getEncoded must contain allElementsOf rsa256PubKey.getEncoded
    }

    "fail to generate a key if the algorithm isn't valid" in {
      Jwk
        .generatePublicKey(bad)
        .failure
        .exception mustBe an[InvalidPublicKeyError]
    }

  }

}
