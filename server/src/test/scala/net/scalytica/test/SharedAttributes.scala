package net.scalytica.test

import com.typesafe.config.Config
import io.github.embeddedkafka.{EmbeddedKafkaConfig, EmbeddedKafkaConfigImpl}
import kafka.server.KafkaConfig.{
  AdvertisedListenersProp,
  AutoCreateTopicsEnableProp,
  InterBrokerListenerNameProp,
  ListenersProp,
  SaslEnabledMechanismsProp,
  SaslMechanismInterBrokerProtocolProp,
  SslEndpointIdentificationAlgorithmProp,
  SslKeyPasswordProp,
  SslKeystoreLocationProp,
  SslKeystorePasswordProp,
  SslTruststoreLocationProp,
  SslTruststorePasswordProp,
  ZkConnectProp,
  ZkConnectionTimeoutMsProp
}
import net.scalytica.kafka.wsproxy.config.Configuration
import net.scalytica.kafka.wsproxy.config.Configuration.{AppCfg, BasicAuthCfg}
import net.scalytica.kafka.wsproxy.models.{WsProducerId, WsServerId}
import net.scalytica.test.FileLoader._
import org.apache.kafka.clients.CommonClientConfigs.SECURITY_PROTOCOL_CONFIG
import org.apache.kafka.common.config.SaslConfigs.{
  SASL_JAAS_CONFIG,
  SASL_MECHANISM
}
import org.apache.kafka.common.config.SslConfigs._
import org.apache.kafka.common.security.auth.SecurityProtocol.SASL_SSL
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
      AutoCreateTopicsEnableProp -> "false",
      ZkConnectionTimeoutMsProp  -> "60000"
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
        ZkConnectProp                          -> s"localhost:$zkp",
        AutoCreateTopicsEnableProp             -> "false",
        AdvertisedListenersProp                -> listeners,
        ListenersProp                          -> listeners,
        InterBrokerListenerNameProp            -> SASL_SSL.name,
        SaslMechanismInterBrokerProtocolProp   -> "PLAIN",
        SaslEnabledMechanismsProp              -> "PLAIN",
        SslEndpointIdentificationAlgorithmProp -> "",
        SslKeystoreLocationProp -> FileLoader
          .filePath("/sasl/kafka/broker1.keystore.jks")
          .toAbsolutePath
          .toString,
        SslKeystorePasswordProp -> testKeyPass,
        SslKeyPasswordProp      -> testKeyPass,
        SslTruststoreLocationProp -> FileLoader
          .filePath("/sasl/kafka/broker1.truststore.jks")
          .toAbsolutePath
          .toString,
        SslTruststorePasswordProp -> testKeyPass,
        saslSslPlainJaasConfig    -> brokerSasl
      ),
      customProducerProperties = secureClientProps,
      customConsumerProperties = secureClientProps
    )
  }

}
