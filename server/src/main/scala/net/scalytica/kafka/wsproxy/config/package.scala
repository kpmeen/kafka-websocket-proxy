package net.scalytica.kafka.wsproxy

import akka.kafka.scaladsl.Consumer
import akka.stream.scaladsl.Source
import net.scalytica.kafka.wsproxy.config.Configuration.{
  AppCfg,
  ClientSpecificLimitCfg,
  ConsumerSpecificLimitCfg,
  DynamicCfg,
  ProducerSpecificLimitCfg
}
import net.scalytica.kafka.wsproxy.models.{WsGroupId, WsProducerId}

package object config {

  /**
   * The type for the Kafka consumer stream used in the
   * [[DynamicConfigHandler]].
   */
  type DynamicConfigSource =
    Source[DynamicConfigHandlerProtocol.InternalCommand, Consumer.Control]

  private[config] def dynCfgConsumerGroupId(implicit cfg: AppCfg): String = {
    s"ws-proxy-dynamic-config-handler-consumer-${cfg.server.serverId.value}"
  }

  /**
   * Build a Kafka key to use for consumer specific [[DynamicCfg]]
   * @param id
   *   The [[WsGroupId]] to build a key from
   * @return
   *   A String value to use as key
   */
  def consumerCfgKey(id: WsGroupId): String =
    "consumer-%s".formatted(id.value)

  /**
   * Build a Kafka key to use for producer specific [[DynamicCfg]]
   * @param id
   *   The [[WsProducerId]] to build a key from
   * @return
   *   A String value to use as key
   */
  def producerCfgKey(id: WsProducerId): String =
    "producer-%s".formatted(id.value)

  /**
   * Generates a valid key to be used on the dynamic config Kafka topic. The key
   * is composed to include the type of dynamic configuration associated with
   * it.
   *
   * @param dc
   *   The [[DynamicCfg]] to derive a key from.
   * @return
   *   The key to use for the Kafka topic
   */
  private[config] def dynamicCfgTopicKey(dc: DynamicCfg): Option[String] = {
    dc match {
      case csl: ClientSpecificLimitCfg =>
        csl match {
          case cs: ConsumerSpecificLimitCfg =>
            Option(consumerCfgKey(cs.groupId))

          case ps: ProducerSpecificLimitCfg =>
            Option(producerCfgKey(ps.producerId))
        }

      case _ => None
    }
  }

}
