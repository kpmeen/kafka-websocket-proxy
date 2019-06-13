package net.scalytica.kafka.wsproxy.codecs

import java.util.Base64

import scala.util.Try

private[codecs] object Binary {

  private[this] val enc = Base64.getEncoder
  private[this] val dec = Base64.getDecoder

  def decodeBase64(base64: String): Try[Array[Byte]] = {
    Try(dec.decode(base64))
  }

  def encodeBase64(data: Array[Byte]): String = {
    enc.encodeToString(data)
  }

}
