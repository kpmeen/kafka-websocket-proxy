package net.scalytica.test

import com.typesafe.config.Config
import io.github.embeddedkafka.{EmbeddedKafkaConfig, EmbeddedKafkaConfigImpl}
import net.scalytica.kafka.wsproxy.config.Configuration
import net.scalytica.kafka.wsproxy.config.Configuration.{AppCfg, BasicAuthCfg}
import net.scalytica.kafka.wsproxy.models.{WsProducerId, WsServerId}
import net.scalytica.test.FileLoader._
import org.apache.kafka.clients.CommonClientConfigs._
import org.apache.kafka.common.config.SaslConfigs._
import org.apache.kafka.common.config.SslConfigs._
import org.apache.kafka.common.config.internals.BrokerSecurityConfigs._
import org.apache.kafka.common.security.auth.SecurityProtocol.SASL_SSL
import org.apache.kafka.network.SocketServerConfigs._
import org.apache.kafka.server.config.ReplicationConfigs._
import org.apache.kafka.server.config.ServerLogConfigs._
import org.apache.kafka.server.config.ZkConfigs.{
  ZK_CONNECTION_TIMEOUT_MS_CONFIG,
  ZK_CONNECT_CONFIG
}
import org.apache.pekko.http.scaladsl.model.headers.BasicHttpCredentials

object SharedAttributes {

  val testKeyPass: String         = "scalytica"
  val kafkaUser: String           = "client"
  val kafkaPass: String           = kafkaUser
  val creds: BasicHttpCredentials = BasicHttpCredentials(kafkaUser, kafkaPass)
  val defaultProducerClientId: WsProducerId =
    WsProducerId("test-producer-client")

  lazy val defaultTypesafeConfig: Config = loadConfig("/application-test.conf")

  lazy val defaultTestAppCfg: AppCfg =
    Configuration.loadConfig(defaultTypesafeConfig)

  def defaultTestAppCfgWithServerId(
      serverId: String
  ): AppCfg =
    defaultTestAppCfg.copy(
      server = defaultTestAppCfg.server.copy(serverId = WsServerId(serverId))
    )

  val basicAuthUser: String  = "basicAuthUser"
  val basicAuthPass: String  = "basicAuthPass"
  val basicAuthRealm: String = "Test Server"

  val basicHttpCreds: BasicHttpCredentials =
    BasicHttpCredentials(basicAuthUser, basicAuthPass)
  val invalidBasicHttpCreds: BasicHttpCredentials =
    BasicHttpCredentials(basicAuthUser, "invalid")

  def basicAuthCredendials(
      useServerBasicAuth: Boolean
  ): Option[BasicAuthCfg] = {
    if (useServerBasicAuth)
      Option(
        BasicAuthCfg(
          username = Option(basicAuthUser),
          password = Option(basicAuthPass),
          realm = Option(basicAuthRealm),
          enabled = true
        )
      )
    else None
  }

  val saslSslPlainJaasConfig: String =
    "listener.name.sasl_ssl.plain.sasl.jaas.config"

  val secureClientProps: Map[String, String] = Map(
    // scalastyle:off line.size.limit
    SASL_MECHANISM                               -> "PLAIN",
    SECURITY_PROTOCOL_CONFIG                     -> SASL_SSL.name,
    SSL_ENDPOINT_IDENTIFICATION_ALGORITHM_CONFIG -> "",
    SSL_TRUSTSTORE_LOCATION_CONFIG -> filePath(
      "/sasl/kafka/client.truststore.jks"
    ).toAbsolutePath.toString,
    SSL_TRUSTSTORE_PASSWORD_CONFIG -> testKeyPass,
    SSL_KEYSTORE_LOCATION_CONFIG -> filePath(
      "/sasl/kafka/client.keystore.jks"
    ).toAbsolutePath.toString,
    SSL_KEYSTORE_PASSWORD_CONFIG -> testKeyPass,
    SSL_KEY_PASSWORD_CONFIG      -> testKeyPass,
    SASL_JAAS_CONFIG -> s"""org.apache.kafka.common.security.plain.PlainLoginModule required username="$kafkaUser" password="$kafkaPass";"""
    // scalastyle:on line.size.limit
  )

  val embeddedKafkaConfig: EmbeddedKafkaConfig = EmbeddedKafkaConfigImpl(
    kafkaPort = 0,
    zooKeeperPort = 0,
    //    schemaRegistryPort = 0,
    customBrokerProperties = Map(
      AUTO_CREATE_TOPICS_ENABLE_CONFIG -> "false",
      ZK_CONNECTION_TIMEOUT_MS_CONFIG  -> "60000"
    ),
    customProducerProperties = Map.empty,
    customConsumerProperties = Map.empty
    //    customSchemaRegistryProperties = Map(
    //      KAFKASTORE_TOPIC_REPLICATION_FACTOR_CONFIG -> "1"
    //    )
  )

  def embeddedKafkaConfigWithSasl: EmbeddedKafkaConfig = {
    val brokerPortSecure = availablePort
    val zkp              = availablePort
    //    val srp              = availablePort

    val brokerSasl =
      "org.apache.kafka.common.security.plain.PlainLoginModule required " +
        """username="admin" """ +
        """password="admin" """ +
        """user_admin="admin" """ +
        """user_broker1="broker1" """ +
        s"""user_$kafkaUser="$kafkaPass";"""

    val listeners = s"SASL_SSL://localhost:$brokerPortSecure"

    EmbeddedKafkaConfig(
      kafkaPort = brokerPortSecure,
      zooKeeperPort = zkp,
      //      schemaRegistryPort = srp,
      customBrokerProperties = Map(
        ZK_CONNECT_CONFIG                            -> s"localhost:$zkp",
        AUTO_CREATE_TOPICS_ENABLE_CONFIG             -> "false",
        ADVERTISED_LISTENERS_CONFIG                  -> listeners,
        LISTENERS_CONFIG                             -> listeners,
        INTER_BROKER_LISTENER_NAME_CONFIG            -> SASL_SSL.name,
        SASL_MECHANISM_INTER_BROKER_PROTOCOL_CONFIG  -> "PLAIN",
        SASL_ENABLED_MECHANISMS_CONFIG               -> "PLAIN",
        SSL_ENDPOINT_IDENTIFICATION_ALGORITHM_CONFIG -> "",
        SSL_KEYSTORE_LOCATION_CONFIG -> FileLoader
          .filePath("/sasl/kafka/broker1.keystore.jks")
          .toAbsolutePath
          .toString,
        SSL_KEYSTORE_PASSWORD_CONFIG -> testKeyPass,
        SSL_KEY_PASSWORD_CONFIG      -> testKeyPass,
        SSL_TRUSTSTORE_LOCATION_CONFIG -> FileLoader
          .filePath("/sasl/kafka/broker1.truststore.jks")
          .toAbsolutePath
          .toString,
        SSL_TRUSTSTORE_PASSWORD_CONFIG -> testKeyPass,
        saslSslPlainJaasConfig         -> brokerSasl
      ),
      customProducerProperties = secureClientProps,
      customConsumerProperties = secureClientProps
    )
  }

}
