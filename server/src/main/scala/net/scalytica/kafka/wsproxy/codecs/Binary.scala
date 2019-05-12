package net.scalytica.kafka.wsproxy.codecs

import javax.xml.bind.DatatypeConverter

import scala.util.Try

private[codecs] object Binary {

  def decodeBase64(base64: String): Try[Array[Byte]] = {
    Try(DatatypeConverter.parseBase64Binary(base64))
  }

  def encodeBase64(data: Array[Byte]): String = {
    DatatypeConverter.printBase64Binary(data)
  }

  def decodeHex(hex: String): Try[Array[Byte]] = {
    Try(DatatypeConverter.parseHexBinary(hex))
  }

  def encodeHex(bytes: Array[Byte]): String = {
    DatatypeConverter.printHexBinary(bytes)
  }

}
