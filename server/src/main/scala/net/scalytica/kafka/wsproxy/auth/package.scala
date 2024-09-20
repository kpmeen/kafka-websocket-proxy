package net.scalytica.kafka.wsproxy

import scala.concurrent.Future

import com.typesafe.scalalogging.Logger
import io.circe.generic.extras.{Configuration => CirceConfiguration}
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import pdi.jwt.JwtBase64
import pdi.jwt.JwtClaim

package object auth {

  implicit private[auth] val circeConfig: CirceConfiguration =
    CirceConfiguration.default.withSnakeCaseMemberNames

  val PubKeyAlgo = "RSA"

  private[auth] def decodeToBigInt(value: Option[String]): BigInt = {
    value.map(v => BigInt(1, JwtBase64.decode(v))).getOrElse(BigInt(0))
  }

  private[auth] def foldBody(
      body: Source[ByteString, Any]
  )(
      implicit mat: Materializer
  ): Future[Option[String]] = {
    body.runFold[Option[String]](None) { (maybeStr, bytes) =>
      val decoded = bytes.decodeSafeString
      maybeStr match {
        case js @ Some(_) => js.map(s => decoded.map(s + _)).getOrElse(decoded)
        case None         => decoded
      }
    }
  }

  implicit private[auth] class JwtClaimExtensions(jc: JwtClaim) {

    def logValidity(
        logger: Logger,
        isValid: Boolean,
        detailedLogging: Boolean = false
    ): Unit = {
      val jwtId =
        if (detailedLogging) jc.jwtId.map(jid => s" with jti: [$jid]")
        else None
      val aud      = jc.audience.map(_.mkString(", ")).getOrElse("not set")
      val iss      = jc.issuer.getOrElse("not set")
      val logMsg   = "Jwt claim%s for audience [%s] from issuer [%s] is %s!"
      val validStr = if (isValid) "valid" else "NOT valid"

      logger.debug(logMsg.format(jwtId.getOrElse(""), aud, iss, validStr))
    }

  }
}
