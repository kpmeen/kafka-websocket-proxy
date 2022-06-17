package net.scalytica.kafka.wsproxy.config

import net.scalytica.kafka.wsproxy.config.Configuration.{
  ConsumerSpecificLimitCfg,
  ProducerSpecificLimitCfg
}
import net.scalytica.kafka.wsproxy.config.DynamicConfigHandlerProtocol._
import org.scalatest.Inspectors.forAll
import org.scalatest.OptionValues
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec

// scalastyle:off magic.number
class DynamicConfigurationsSpec
    extends AnyWordSpec
    with Matchers
    with OptionValues
    with DynamicConfigTestDataGenerators {

  val expected: DynamicConfigurations = DynamicConfigurations(expectedMap)

  private[this] val clsNameProdSpecLimitCfg =
    classOf[ProducerSpecificLimitCfg]

  private[this] val clsNameConsSpecLimitCfg =
    classOf[ConsumerSpecificLimitCfg]

  "DynamicConfigurations" should {

    "be initialised with an empty configs map" in {
      DynamicConfigurations().configs mustBe empty
    }

    "be initialised with a non-empty configs map" in {
      expected.configs must contain allElementsOf expectedMap
    }

    "be initialised with a list of UpdateDynamicConfigRecord instances" in {
      val records = expectedMap.zipWithIndex.map { case ((k, v), i) =>
        UpdateDynamicConfigRecord(k, v, i.toLong)
      }.toSeq
      val res = DynamicConfigurations(records: _*).configs
      res must contain allElementsOf expectedMap
    }

    "return none if config for a key is not found" in {
      expected.findByKey("foobar") mustBe None
    }

    "return the config if config for a key is found" in {
      forAll(expectedMap) { case (k, v) =>
        expected.findByKey(k).value mustBe v
      }
    }

    "return false if config map does not contain key" in {
      expected.hasKey("foobar") mustBe false
    }

    "return true if config map does not contain key" in {
      forAll(expectedKeys) { key =>
        expected.hasKey(key) mustBe true
      }
    }

    "add a new dynamic configuration" in {
      val cfg = createConsumerLimitTestCfg("g-4", 4, 40)
      val key = dynamicCfgTopicKey(cfg).value
      val add = UpdateDynamicConfigRecord(key, cfg, 4)

      val res = expected.update(add).value

      res.hasKey(key) mustBe true
      res.findByKey(key).value mustBe cfg
    }

    "update an existing dynamic configuration" in {
      val (key, value) = expectedSeq(2)

      value match {
        case orig: ProducerSpecificLimitCfg =>
          val cfg = orig.copy(messagesPerSecond = Some(200))
          val upd = UpdateDynamicConfigRecord(key, cfg, 10)

          val res = expected.update(upd).value

          res.hasKey(key) mustBe true
          res.findByKey(key).value mustBe cfg

        case _ =>
          fail(
            s"Expected a $clsNameProdSpecLimitCfg, " +
              s"but got a $clsNameConsSpecLimitCfg"
          )
      }
    }

    "remove a dynamic configuration" in {
      val remKey = expectedKeys.lastOption.value

      val res = expected.remove(RemoveDynamicConfigRecord(remKey, 20)).value

      res.hasKey(remKey) mustBe false
      res.findByKey(remKey) mustBe None
    }

    "remove all dynamic configurations" in {
      expected.removeAll().isEmpty mustBe true
    }
  }
}
