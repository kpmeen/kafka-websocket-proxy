package net.scalytica.kafka.wsproxy

import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import io.circe.generic.extras.{Configuration => CirceConfiguration}
import pdi.jwt.JwtBase64

import scala.concurrent.Future

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
}
