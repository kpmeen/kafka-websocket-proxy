package net.scalytica.kafka.wsproxy

import io.confluent.kafka.serializers.AbstractKafkaAvroSerDeConfig.{
  AUTO_REGISTER_SCHEMAS,
  SCHEMA_REGISTRY_URL_CONFIG
}
import net.scalytica.kafka.wsproxy.Configuration.AppCfg

trait WithSchemaRegistryConfig {

  protected def schemaRegistryCfg(
      implicit cfg: AppCfg
  ): Option[Map[String, AnyRef]] = {
    cfg.kafkaClient.schemaRegistry.map { s =>
      Map[String, AnyRef](
        SCHEMA_REGISTRY_URL_CONFIG -> s.url,
        AUTO_REGISTER_SCHEMAS      -> Boolean.box(s.autoRegisterSchemas)
      ) ++ s.properties
    }
  }

}
