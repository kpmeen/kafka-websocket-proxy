import Dependencies._
import Settings._
import net.scalytica.sbt.plugin.DockerTasksPlugin

import scala.language.postfixOps

// scalastyle:off

name := "kafka-websocket-proxy"
version := "0.1"

lazy val root = (project in file("."))
  .enablePlugins(DockerPlugin, DockerTasksPlugin)
  .settings(BaseSettings: _*)
  .settings(NoPublish)
  .settings(resolvers ++= Dependencies.Resolvers)
  .settings(scalaVersion := Versions.ScalaVersion)
  .settings(dockerSettings(Some(9000)))
  .settings(scalastyleFailOnWarning := true)
  .settings(libraryDependencies ++= Config.All)
  .settings(libraryDependencies ++= Circe.All)
  .settings(
    libraryDependencies ++= Seq(
      Akka.AkkaActor,
      Akka.AkkaTyped,
      Akka.AkkaSlf4j,
      Akka.AkkaStream,
      Akka.AkkaStreamTyped,
      Akka.AkkaHttp,
      Akka.AkkaStreamKafka,
      Avro.Avro4sKafka,
      Kafka.Kafka,
      Kafka.Clients,
      Kafka.MonitoringInterceptors,
      Logging.Logback,
      Logging.Log4jOverSlf4j         % Test,
      Logging.JulToSlf4j             % Test,
      Testing.ScalaTest              % Test,
      Testing.EmbeddedKafka          % Test,
      Testing.EmbeddedSchemaRegistry % Test,
      Testing.AkkaTestkit            % Test,
      Testing.Scalactic              % Test
    )
  )
