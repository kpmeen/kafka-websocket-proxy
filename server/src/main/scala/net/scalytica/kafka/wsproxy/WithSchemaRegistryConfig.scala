package net.scalytica.kafka.wsproxy

import io.confluent.kafka.serializers.AbstractKafkaAvroSerDeConfig.{
  AUTO_REGISTER_SCHEMAS,
  SCHEMA_REGISTRY_URL_CONFIG
}
import net.scalytica.kafka.wsproxy.Configuration.AppCfg

trait WithSchemaRegistryConfig {

  protected def schemaRegistryCfg(registryUrl: String)(implicit cfg: AppCfg) =
    Map[String, Any](
      SCHEMA_REGISTRY_URL_CONFIG -> registryUrl,
      AUTO_REGISTER_SCHEMAS      -> cfg.kafkaClient.autoRegisterSchemas
    )

}
