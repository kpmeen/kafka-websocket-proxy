package net.scalytica.kafka.wsproxy.codecs

import net.scalytica.test.TestDataGenerators
import org.scalatest.OptionValues
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec

class WsProxyAvroSerdeSpec
    extends AnyWordSpec
    with Matchers
    with OptionValues
    with TestDataGenerators
    with ProtocolSerdes {

  "The WsProxyAvroSerde" should {

    "convert an AvroProducerRecord with headers and an Avro key" in {
      val o =
        createAvroProducerRecordKeyValue(1, withHeaders = true).headOption.value

      val bytes = avroProducerRecordSerde.serialize(o)
      bytes must not be empty
      val res = avroProducerRecordSerde.deserialize(bytes)

      val okb = o.key.value.select[Array[Byte]].value
      val rkb = res.key.value.select[Array[Byte]].value

      val okInst = TestSerdes.keySerdes.deserialize(okb)
      val rkInst = TestSerdes.keySerdes.deserialize(rkb)

      rkInst mustBe okInst

      val ovb = o.value.select[Array[Byte]].value
      val rvb = res.value.select[Array[Byte]].value

      val ovInst = TestSerdes.valueSerdes.deserialize(ovb)
      val rvInst = TestSerdes.valueSerdes.deserialize(rvb)

      rvInst mustBe ovInst

      res.headers.value must have size 1
    }

    "convert an AvroProducerRecord with headers and a String key" in {
      val o = createAvroProducerRecordStringBytes(
        1,
        withHeaders = true
      ).headOption.value

      val bytes = avroProducerRecordSerde.serialize(o)
      bytes must not be empty
      val res = avroProducerRecordSerde.deserialize(bytes)

      res.key mustBe o.key
      res.headers mustBe o.headers

      val ovb = o.value.select[Array[Byte]].value
      val rvb = res.value.select[Array[Byte]].value

      val ovInst = TestSerdes.valueSerdes.deserialize(ovb)
      val rvInst = TestSerdes.valueSerdes.deserialize(rvb)

      rvInst mustBe ovInst
    }
  }

}
