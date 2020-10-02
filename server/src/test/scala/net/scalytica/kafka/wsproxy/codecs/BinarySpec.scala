package net.scalytica.kafka.wsproxy.codecs

import java.nio.charset.StandardCharsets

import org.scalatest.TryValues
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec

class BinarySpec extends AnyWordSpec with Matchers with TryValues {

  val unencodedString = "foobar"
  val unencodedBytes  = unencodedString.getBytes(StandardCharsets.UTF_8)
  val encodedString   = "Zm9vYmFy"

  "Binary" should {

    "encode a String with base64" in {
      Binary.encodeBase64(unencodedBytes) mustBe encodedString
    }

    "decode a base64 encoded String" in {
      Binary
        .decodeBase64(encodedString)
        .success
        .value must contain allElementsOf unencodedBytes
    }

  }

}
