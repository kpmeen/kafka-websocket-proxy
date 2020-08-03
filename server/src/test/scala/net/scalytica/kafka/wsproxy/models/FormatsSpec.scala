package net.scalytica.kafka.wsproxy.models

import net.scalytica.kafka.wsproxy.models.Formats._
import net.scalytica.kafka.wsproxy.models.Formats.FormatType._
import org.scalatest.OptionValues
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec

class FormatsSpec extends AnyWordSpec with Matchers with OptionValues {

  "Format types" should {

    "be possible to initialise a JsonType using a string value" in {
      fromString("json").value mustBe JsonType
    }

    "be possible to initialise a AvroType using a string value" in {
      fromString("avro").value mustBe AvroType
    }

    "be possible to initialise a ByteArrayType using a string value" in {
      fromString("byte_array").value mustBe ByteArrayType
    }

    "be possible to initialise a StringType using a string value" in {
      fromString("string").value mustBe StringType
    }

    "be possible to initialise a IntType using a string value" in {
      fromString("int").value mustBe IntType
    }

    "be possible to initialise a LongType using a string value" in {
      fromString("long").value mustBe LongType
    }

    "be possible to initialise a DoubleType using a string value" in {
      fromString("double").value mustBe DoubleType
    }

    "be possible to initialise a FloatType using a string value" in {
      fromString("float").value mustBe FloatType
    }

    "not care about case when initialising FormatType from string value" in {
      fromString("STRING").value mustBe StringType
      fromString("sTrInG").value mustBe StringType
    }

    "not initialise a FormatType from an unknown string value" in {
      fromString("bytearray") mustBe None
    }

    "throw exception when unsafely initialising from invalid string value" in {
      an[IllegalArgumentException] must be thrownBy unsafeFromString("foobar")
    }

    "use snake_case for the name string" in {
      ByteArrayType.name mustBe "byte_array"
    }

  }

}
