import sbt._

object Versions {

  val ScalaVersion = "2.13.3"

  val ConfigVersion     = "1.4.0"
  val PureConfigVersion = "0.14.0"

  val Avro4sVersion                 = "4.0.0"
  val ConfluentPlatformVersion      = "6.0.0"
  val KafkaVersion                  = "2.6.0"
  val EmbeddedKafkaVersion          = "2.6.0"
  val EmbeddedSchemaRegistryVersion = "6.0.0"
  val KafkaStreamsQueryVersion      = "0.1.1"

  val AkkaVersion            = "2.6.10"
  val AkkaHttpVersion        = "10.2.1"
  val AkkaStreamKafkaVersion = "2.0.5"

  val AlpakkaVersion = "1.0.2"

  val AkkaHttpCirceVersion      = "1.25.2"
  val CirceVersion              = "0.13.0"
  val CirceGenericExtrasVersion = CirceVersion
  val CirceOpticsVersion        = CirceVersion

  val JwtScalaVersion = "4.3.0"

  val ScalaLoggingVersion = "3.9.2"
  val Slf4JVersion        = "1.7.30"
  val LogbackVersion      = "1.2.3"

  val ScalaTestVersion = "3.2.3"

  val JolokiaAgentVersion    = "1.6.2"
  val PrometheusAgentVersion = "0.14.0"

  val GatlingVersion = "3.1.1"
}

object Dependencies {
  // scalastyle:off

  import Versions._

  val Resolvers: Seq[Resolver] =
    DefaultOptions.resolvers(snapshot = true) ++ Seq(
      Resolver.typesafeRepo("releases"),
      Resolver.jcenterRepo,
      Resolver.bintrayRepo("hseeberger", "maven"),
      MavenRepo("Confluent", "https://packages.confluent.io/maven/"),
      MavenRepo(
        "MuleSoft",
        "https://repository.mulesoft.org/nexus/content/repositories/public/"
      ),
      MavenRepo(
        "Avro4s-Snapshots",
        "https://oss.sonatype.org/content/repositories/snapshots"
      )
    )

  private[this] val LoggerExclusionsTest = Seq(
    ExclusionRule("log4j", "log4j"),
    ExclusionRule("org.slf4j", "slf4j-log4j12")
  )

  private[this] val Exclusions =
    LoggerExclusionsTest ++
      Seq(ExclusionRule("org.apache.zookeeper", "zookeeper"))

  object Akka {

    val Actor        = "com.typesafe.akka" %% "akka-actor"         % AkkaVersion
    val Stream       = "com.typesafe.akka" %% "akka-stream"        % AkkaVersion
    val ActorTyped   = "com.typesafe.akka" %% "akka-actor-typed"   % AkkaVersion
    val StreamTyped  = "com.typesafe.akka" %% "akka-stream-typed"  % AkkaVersion
    val ClusterTyped = "com.typesafe.akka" %% "akka-cluster-typed" % AkkaVersion

    val DistDataTyped =
      "com.typesafe.akka" %% "akka-cluster-typed" % AkkaVersion
    val Slf4j = "com.typesafe.akka" %% "akka-slf4j" % AkkaVersion
    val Http  = "com.typesafe.akka" %% "akka-http"  % AkkaHttpVersion

    val AkkaStreamKafka =
      "com.typesafe.akka" %% "akka-stream-kafka" % AkkaStreamKafkaVersion

    val AlpakkaCsv =
      "com.lightbend.akka" %% "akka-stream-alpakka-csv" % AlpakkaVersion
  }

  object OAuth {
    val JwtScala = "com.pauldijou" %% "jwt-core"  % JwtScalaVersion
    val JwtCirce = "com.pauldijou" %% "jwt-circe" % JwtScalaVersion

    val All = Seq(JwtScala, JwtCirce)
  }

  object Avro {
    val Avro4sCore  = "com.sksamuel.avro4s" %% "avro4s-core"  % Avro4sVersion
    val Avro4sKafka = "com.sksamuel.avro4s" %% "avro4s-kafka" % Avro4sVersion
    val Avro4sJson  = "com.sksamuel.avro4s" %% "avro4s-json"  % Avro4sVersion

    val All = Seq(Avro4sCore, Avro4sKafka)
  }

  object Kafka {

    // official kafka libs
    val Clients =
      "org.apache.kafka" % "kafka-clients" % KafkaVersion excludeAll (Exclusions: _*)

    val Streams =
      "org.apache.kafka" % "kafka-streams" % KafkaVersion excludeAll (Exclusions: _*)

    val Kafka =
      "org.apache.kafka" %% "kafka" % KafkaVersion excludeAll (Exclusions: _*)

    // kafka-streams-scala libs
    val StreamsScala =
      "org.apache.kafka" %% "kafka-streams-scala" % KafkaVersion excludeAll (Exclusions: _*)

    val StreamsQuery =
      "com.lightbend" %% "kafka-streams-query" % KafkaStreamsQueryVersion excludeAll (Exclusions: _*)
  }

  object ConfluentKafka {

    val AvroSerializer =
      "io.confluent" % "kafka-avro-serializer" % ConfluentPlatformVersion excludeAll (Exclusions: _*)

    val JsonSerializer =
      "io.confluent" % "kafka-json-serializer" % ConfluentPlatformVersion excludeAll (Exclusions: _*)

    val StreamsAvroSerde =
      "io.confluent" % "kafka-streams-avro-serde" % ConfluentPlatformVersion excludeAll (Exclusions: _*)

    val SchemaRegistry =
      "io.confluent" % "kafka-schema-registry" % ConfluentPlatformVersion excludeAll (Exclusions: _*)

    val SchemaRegistryClient =
      "io.confluent" % "kafka-schema-registry-client" % ConfluentPlatformVersion excludeAll (Exclusions: _*)

    val MonitoringInterceptors =
      "io.confluent" % "monitoring-interceptors" % ConfluentPlatformVersion excludeAll (Exclusions: _*)
  }

  object Config {
    val TypeSafeConfig = "com.typesafe"           % "config"     % ConfigVersion
    val PureConfig     = "com.github.pureconfig" %% "pureconfig" % PureConfigVersion

    val All = Seq(TypeSafeConfig, PureConfig)
  }

  object Circe {

    val AkkaHttpSupport =
      "de.heikoseeberger" %% "akka-http-circe" % AkkaHttpCirceVersion

    val Optics = "io.circe" %% "circe-optics" % CirceOpticsVersion

    val Extras =
      "io.circe" %% "circe-generic-extras" % CirceGenericExtrasVersion

    val All = Seq(
      "io.circe" %% "circe-core",
      "io.circe" %% "circe-generic",
      "io.circe" %% "circe-parser"
    ).map(_ % CirceVersion) :+ Optics :+ Extras
  }

  object Testing {
    val ScalaTest = "org.scalatest" %% "scalatest" % ScalaTestVersion
    val Scalactic = "org.scalactic" %% "scalactic" % ScalaTestVersion

    val ScalaTestDeps = Seq(ScalaTest % Test, Scalactic)

    val EmbeddedKafka =
      "io.github.embeddedkafka" %% "embedded-kafka" % EmbeddedKafkaVersion excludeAll (LoggerExclusionsTest: _*)

    val EmbeddedKafkaStreams =
      "io.github.embeddedkafka" %% "embedded-kafka-streams" % EmbeddedKafkaVersion excludeAll (LoggerExclusionsTest: _*)

    val EmbeddedSchemaRegistry =
      "io.github.embeddedkafka" %% "embedded-kafka-schema-registry" % EmbeddedSchemaRegistryVersion excludeAll (LoggerExclusionsTest: _*)

    val AkkaTestKit = "com.typesafe.akka" %% "akka-testkit" % AkkaVersion

    val AkkaTypedTestKit =
      "com.typesafe.akka" %% "akka-actor-testkit-typed" % AkkaVersion

    val AkkaHttpTestKit =
      "com.typesafe.akka" %% "akka-http-testkit" % AkkaHttpVersion

    val AkkaStreamTestKit =
      "com.typesafe.akka" %% "akka-stream-testkit" % AkkaVersion

    val AkkaStreamKafkaTestKit =
      "com.typesafe.akka" %% "akka-stream-kafka-testkit" % AkkaStreamKafkaVersion
  }

  object GatlingDeps {

    val GatlingHighcharts =
      "io.gatling.highcharts" % "gatling-charts-highcharts" % GatlingVersion % "test"

    val GatlingTest =
      "io.gatling" % "gatling-test-framework" % GatlingVersion % "test"

    val All = Seq(GatlingHighcharts, GatlingTest)
  }

  object Logging {

    val ScalaLogging =
      "com.typesafe.scala-logging" %% "scala-logging" % ScalaLoggingVersion
    val Logback        = "ch.qos.logback" % "logback-classic"  % LogbackVersion
    val Slf4j          = "org.slf4j"      % "slf4j-api"        % Slf4JVersion
    val Log4jOverSlf4j = "org.slf4j"      % "log4j-over-slf4j" % Slf4JVersion
    val Slf4jLog4j     = "org.slf4j"      % "slf4j-log4j12"    % Slf4JVersion
    val JulToSlf4j     = "org.slf4j"      % "jul-to-slf4j"     % Slf4JVersion
    val Slf4jNop       = "org.slf4j"      % "slf4j-nop"        % Slf4JVersion

    val All = Seq(ScalaLogging, Slf4j, Logback)
  }

  object Monitoring {

    val JolokiaAgent =
      "org.jolokia" % "jolokia-jvm" % JolokiaAgentVersion classifier "agent"

    val PrometheusAgent =
      "io.prometheus.jmx" % "jmx_prometheus_javaagent" % PrometheusAgentVersion

    val All = Seq(JolokiaAgent, PrometheusAgent)
  }

  object Overrides {

    val Deps = Seq(
      "org.apache.kafka"   % "kafka-clients"     % KafkaVersion,
      "org.apache.kafka"   % "kafka-streams"     % KafkaVersion,
      "org.apache.kafka"  %% "kafka"             % KafkaVersion,
      "com.typesafe.akka" %% "akka-stream-kafka" % AkkaStreamKafkaVersion,
      "org.slf4j"          % "slf4j-api"         % Slf4JVersion,
      "org.slf4j"          % "log4j-over-slf4j"  % Slf4JVersion,
      "org.slf4j"          % "slf4j-log4j12"     % Slf4JVersion,
      "org.slf4j"          % "jul-to-slf4j"      % Slf4JVersion,
      "org.slf4j"          % "slf4j-nop"         % Slf4JVersion
    )
  }
}
