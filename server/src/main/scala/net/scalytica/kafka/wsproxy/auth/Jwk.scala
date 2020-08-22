package net.scalytica.kafka.wsproxy.auth

import java.security.spec.{InvalidKeySpecException, RSAPublicKeySpec}
import java.security.{KeyFactory, NoSuchAlgorithmException, PublicKey}

import net.scalytica.kafka.wsproxy.errors.InvalidPublicKeyError

import scala.util.{Failure, Try}

/**
 * @param kty
 *   The family of cryptographic algorithms used with the key.
 * @param kid
 *   The unique identifier for the key.
 * @param alg
 *   The specific cryptographic algorithm used with the key.
 * @param use
 *   How the key was meant to be used; sig represents the signature.
 * @param key_ops
 *   The operation(s) for which the key is intended to be used:
 *   - "sign" (compute digital signature or MAC)
 *   - "verify" (verify digital signature or MAC)
 *   - "encrypt" (encrypt content)
 *   - "decrypt" (decrypt content and validate decryption, if applicable)
 *   - "wrapKey" (encrypt key)
 *   - "unwrapKey" (decrypt key and validate decryption, if applicable)
 *   - "deriveKey" (derive key)
 *   - "deriveBits" (derive bits not to be used as a key)
 * @param x5u
 *   URL for the X.509 public key certificate or certificate chain.
 * @param x5c
 *   The x.509 certificate chain. The first entry in the array is the
 *   certificate to use for token verification; the other certificates can be
 *   used to verify this first certificate.
 * @param x5t
 *   The thumbprint of the x.509 cert (SHA-1 thumbprint).
 * @param n
 *   The modulus for the RSA public key.
 * @param e
 *   The exponent for the RSA public key.
 *
 * @see
 *   https://tools.ietf.org/html/rfc7517
 */
case class Jwk(
    kty: String,
    kid: Option[String],
    alg: Option[String],
    use: Option[String],
    key_ops: Option[List[String]],
    x5u: Option[String],
    x5c: Option[List[String]],
    x5t: Option[String],
    n: Option[String],
    e: Option[String]
) {

  override def toString = {
    // format: off
    // scalastyle:off
    s"""Jwk(
       |      kty=$kty
       |      kid=$kid
       |      alg=$alg
       |      use=$use
       |  key_ops=${key_ops.map(_.mkString("[", ", ", "]")).getOrElse("[ ]")}
       |      x5u=$x5u
       |      x5c=${x5c.map(_.mkString("[", ", ", "]")).getOrElse("[ ]")}
       |      x5t=$x5t
       |        n=$n
       |        e=$e
       |)""".stripMargin
    // scalastyle:on
    // format: on
  }

}

object Jwk {

  private[auth] val PubKeyAlgo = "RSA"

  private[auth] lazy val keyFactory = KeyFactory.getInstance(PubKeyAlgo)

  private[auth] def generatePublicKey(jwk: Jwk): Try[PublicKey] = {
    if (!PubKeyAlgo.equalsIgnoreCase(jwk.kty)) {
      Failure(
        InvalidPublicKeyError(s"The key type '${jwk.kty}' is not $PubKeyAlgo")
      )
    } else {
      Try {
        val mod = decodeToBigInt(jwk.n)
        val exp = decodeToBigInt(jwk.e)

        keyFactory.generatePublic(
          new RSAPublicKeySpec(mod.underlying, exp.underlying)
        )
      }.recover {
        case ikse: InvalidKeySpecException =>
          throw InvalidPublicKeyError(
            message = "Invalid public key",
            cause = Option(ikse)
          )

        case nsae: NoSuchAlgorithmException =>
          throw InvalidPublicKeyError(
            message = "Invalid algorithm to generate key",
            cause = Option(nsae)
          )
      }
    }
  }
}
