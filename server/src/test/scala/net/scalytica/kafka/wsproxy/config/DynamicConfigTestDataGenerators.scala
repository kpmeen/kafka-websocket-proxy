package net.scalytica.kafka.wsproxy.config

import net.scalytica.kafka.wsproxy.config.Configuration.DynamicCfg
import net.scalytica.kafka.wsproxy.OptionExtensions

// scalastyle:off magic.number
trait DynamicConfigTestDataGenerators {

  private[config] def createProducerLimitTestCfg(
      pid: String,
      maxCons: Int,
      msgSec: Int
  ): DynamicCfg = {
    // scalastyle:off line.size.limit
    Configuration.loadDynamicCfgString(
      s"""{"max-connections":$maxCons,"messages-per-second":$msgSec,"producer-id":"$pid"}"""
    )
    // scalastyle:on line.size.limit
  }

  private[config] def createConsumerLimitTestCfg(
      gid: String,
      maxCons: Int,
      msgSec: Int
  ): DynamicCfg = {
    // scalastyle:off line.size.limit
    Configuration.loadDynamicCfgString(
      s"""{"group-id":"$gid","max-connections":$maxCons,"messages-per-second":$msgSec}"""
    )
    // scalastyle:on line.size.limit
  }

  private[config] lazy val cfg1 = createProducerLimitTestCfg("p-1", 1, 10)
  private[config] lazy val cfg2 = createConsumerLimitTestCfg("g-1", 1, 10)
  private[config] lazy val cfg3 = createProducerLimitTestCfg("p-2", 2, 20)
  private[config] lazy val cfg4 = createConsumerLimitTestCfg("g-2", 2, 20)
  private[config] lazy val cfg5 = createProducerLimitTestCfg("p-3", 3, 30)
  private[config] lazy val cfg6 = createConsumerLimitTestCfg("g-3", 3, 30)

  private[config] val expectedSeq: Seq[(String, DynamicCfg)] = Seq(
    dynamicCfgTopicKey(cfg1).getUnsafe -> cfg1,
    dynamicCfgTopicKey(cfg2).getUnsafe -> cfg2,
    dynamicCfgTopicKey(cfg3).getUnsafe -> cfg3,
    dynamicCfgTopicKey(cfg4).getUnsafe -> cfg4,
    dynamicCfgTopicKey(cfg5).getUnsafe -> cfg5,
    dynamicCfgTopicKey(cfg6).getUnsafe -> cfg6
  )

  private[config] val expectedMap: Map[String, DynamicCfg] = expectedSeq.toMap

  private[config] val expectedKeys   = expectedSeq.map(_._1)
  private[config] val expectedValues = expectedSeq.map(_._2)

}
// scalastyle:on magic.number
