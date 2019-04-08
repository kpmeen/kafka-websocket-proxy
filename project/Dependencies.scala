import sbt._

object Versions {
  val ScalaVersion = "2.12.8"

  val ConfigVersion     = "1.3.3"
  val PureConfigVersion = "0.10.2"

  val Avro4sVersion                 = "2.0.4"
  val ConfluentPlatformVersion      = "5.2.0"
  val KafkaVersion                  = "2.2.0"
  val EmbeddedKafkaVersion          = "2.2.0"
  val EmbeddedSchemaRegistryVersion = "5.2.0"
  val KafkaStreamsQueryVersion      = "0.1.1"

  val AkkaVersion            = "2.5.21"
  val AkkaHttpVersion        = "10.1.8"
  val AkkaStreamKafkaVersion = "1.0.1"

  val AlpakkaVersion = "0.20"

  val AkkaHttpCirceVersion = "1.25.2"
  val CirceVersion         = "0.11.1"
  val CirceOpticsVersion   = "0.11.0"

  val ScalaLoggingVersion = "3.9.2"
  val Slf4JVersion        = "1.7.26"
  val LogbackVersion      = "1.2.3"

  val ScalaTestVersion         = "3.0.7"
  val ScalaTestPlusPlayVersion = "4.0.1"
}

object Dependencies {
  // scalastyle:off

  import Versions._

  val Resolvers: Seq[Resolver] =
    DefaultOptions.resolvers(snapshot = true) ++ Seq(
      Resolver.typesafeRepo("releases"),
      Resolver.jcenterRepo,
      MavenRepo("confluent", "http://packages.confluent.io/maven/"),
      Resolver.bintrayRepo("hseeberger", "maven")
    )

  object Akka {

    val AkkaActor       = "com.typesafe.akka" %% "akka-actor"        % AkkaVersion
    val AkkaStream      = "com.typesafe.akka" %% "akka-stream"       % AkkaVersion
    val AkkaTyped       = "com.typesafe.akka" %% "akka-actor-typed"  % AkkaVersion
    val AkkaStreamTyped = "com.typesafe.akka" %% "akka-stream-typed" % AkkaVersion
    val AkkaSlf4j       = "com.typesafe.akka" %% "akka-slf4j"        % AkkaVersion
    val AkkaHttp        = "com.typesafe.akka" %% "akka-http"         % AkkaHttpVersion

    val AkkaStreamKafka = "com.typesafe.akka" %% "akka-stream-kafka" % AkkaStreamKafkaVersion

    val AlpakkaCsv = "com.lightbend.akka" %% "akka-stream-alpakka-csv" % AlpakkaVersion
  }

  object Avro {
    val Avro4sCore   = "com.sksamuel.avro4s" %% "avro4s-core"   % Avro4sVersion
    val Avro4sMacros = "com.sksamuel.avro4s" %% "avro4s-macros" % Avro4sVersion
    val Avro4sKafka  = "com.sksamuel.avro4s" %% "avro4s-kafka"  % Avro4sVersion
    val Avro4sJson   = "com.sksamuel.avro4s" %% "avro4s-json"   % Avro4sVersion

    val All = Seq(Avro4sCore, Avro4sMacros, Avro4sKafka)
  }

  object Kafka {
    private[this] val LoggerExclusions = Seq(
      ExclusionRule("log4j", "log4j"),
      ExclusionRule("org.slf4j", "slf4j-log4j12"),
      ExclusionRule("org.apache.zookeeper", "zookeeper")
    )
    // official kafka libs
    val Clients = "org.apache.kafka" % "kafka-clients" % KafkaVersion excludeAll (LoggerExclusions: _*)
    val Streams = "org.apache.kafka" % "kafka-streams" % KafkaVersion excludeAll (LoggerExclusions: _*)

    val Kafka = "org.apache.kafka" %% "kafka" % KafkaVersion excludeAll (LoggerExclusions: _*)

    // kafka-streams-scala libs
    val StreamsScala = "org.apache.kafka" %% "kafka-streams-scala" % KafkaVersion excludeAll (LoggerExclusions: _*)
    val StreamsQuery = "com.lightbend"    %% "kafka-streams-query" % KafkaStreamsQueryVersion excludeAll (LoggerExclusions: _*)

    val AvroSerializer   = "io.confluent" % "kafka-avro-serializer"    % ConfluentPlatformVersion excludeAll (LoggerExclusions: _*)
    val JsonSerializer   = "io.confluent" % "kafka-json-serializer"    % ConfluentPlatformVersion excludeAll (LoggerExclusions: _*)
    val StreamsAvroSerde = "io.confluent" % "kafka-streams-avro-serde" % ConfluentPlatformVersion excludeAll (LoggerExclusions: _*)

    val MonitoringInterceptors = "io.confluent" % "monitoring-interceptors" % ConfluentPlatformVersion excludeAll (LoggerExclusions: _*)
  }

  object Config {
    val TypeSafeConfig = "com.typesafe"          % "config"      % ConfigVersion
    val PureConfig     = "com.github.pureconfig" %% "pureconfig" % PureConfigVersion

    val All = Seq(TypeSafeConfig, PureConfig)
  }

  object Circe {
    val AkkaHttpSupport = "de.heikoseeberger" %% "akka-http-circe" % AkkaHttpCirceVersion

    val Optics = "io.circe" %% "circe-optics" % CirceOpticsVersion

    val All = Seq(
      "io.circe" %% "circe-core",
      "io.circe" %% "circe-generic",
      "io.circe" %% "circe-generic-extras",
      "io.circe" %% "circe-parser"
    ).map(_ % CirceVersion) :+ Optics
  }

  object Testing {

    private[this] val LoggerExclusions = Seq(
      ExclusionRule("log4j", "log4j"),
      ExclusionRule("org.slf4j", "slf4j-log4j12")
    )

    val ScalaTest = "org.scalatest" %% "scalatest" % ScalaTestVersion
    val Scalactic = "org.scalactic" %% "scalactic" % ScalaTestVersion

    val ScalaTestDeps = Seq(ScalaTest % Test, Scalactic)

    val EmbeddedKafka          = "io.github.embeddedkafka" %% "embedded-kafka"                 % EmbeddedKafkaVersion excludeAll (LoggerExclusions: _*)
    val EmbeddedKafkaStreams   = "io.github.embeddedkafka" %% "embedded-kafka-streams"         % EmbeddedKafkaVersion excludeAll (LoggerExclusions: _*)
    val EmbeddedSchemaRegistry = "io.github.embeddedkafka" %% "embedded-kafka-schema-registry" % EmbeddedSchemaRegistryVersion excludeAll (LoggerExclusions: _*)

    val AkkaTestKit       = "com.typesafe.akka" %% "akka-testkit"             % AkkaVersion
    val AkkaTypedTestKit  = "com.typesafe.akka" %% "akka-actor-testkit-typed" % AkkaVersion
    val AkkaHttpTestKit   = "com.typesafe.akka" %% "akka-http-testkit"        % AkkaHttpVersion
    val AkkaStreamTestKit = "com.typesafe.akka" %% "akka-stream-testkit"      % AkkaVersion
  }

  object Logging {
    val ScalaLogging   = "com.typesafe.scala-logging" %% "scala-logging"   % ScalaLoggingVersion
    val Logback        = "ch.qos.logback"             % "logback-classic"  % LogbackVersion
    val Slf4j          = "org.slf4j"                  % "slf4j-api"        % Slf4JVersion
    val Log4jOverSlf4j = "org.slf4j"                  % "log4j-over-slf4j" % Slf4JVersion
    val Slf4jLog4j     = "org.slf4j"                  % "slf4j-log4j12"    % Slf4JVersion
    val JulToSlf4j     = "org.slf4j"                  % "jul-to-slf4j"     % Slf4JVersion
    val Slf4jNop       = "org.slf4j"                  % "slf4j-nop"        % Slf4JVersion

    val All = Seq(ScalaLogging, Slf4j, Logback)
  }
}
