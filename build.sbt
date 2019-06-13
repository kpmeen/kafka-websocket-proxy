import Dependencies._
import Settings._
import net.scalytica.sbt.plugin.DockerTasksPlugin
import sbtrelease.ReleaseStateTransformations._

import scala.language.postfixOps

// scalastyle:off

name := "kafka-websocket-proxy"

releaseProcess := Seq[ReleaseStep](
  checkSnapshotDependencies, // : ReleaseStep
  inquireVersions,           // : ReleaseStep
  runClean,                  // : ReleaseStep
  runTest,                   // : ReleaseStep
  setReleaseVersion,         // : ReleaseStep
  commitReleaseVersion,      // : ReleaseStep, performs the initial git checks
  tagRelease,                // : ReleaseStep
  setNextVersion,            // : ReleaseStep
  commitNextVersion,         // : ReleaseStep
  pushChanges                // : ReleaseStep, also checks that an upstream branch is properly configured
)

lazy val root = (project in file("."))
  .enablePlugins(DockerTasksPlugin)
  .settings(BaseSettings: _*)
  .settings(NoPublish)
  .aggregate(avro, server)

lazy val avro = (project in file("avro"))
  .settings(BaseSettings: _*)
  .settings(NoPublish)
  .settings(resolvers ++= Dependencies.Resolvers)
  .settings(scalastyleFailOnWarning := true)
  .settings(
    coverageExcludedPackages := "<empty>;net.scalytica.kafka.wsproxy.avro.*;"
  )
  .settings(libraryDependencies ++= Avro.All)

lazy val server = (project in file("server"))
  .enablePlugins(JavaServerAppPackaging, DockerPlugin)
  .settings(NoPublish)
  .settings(BaseSettings: _*)
  .settings(dockerSettings(Some(8078)))
  .settings(resolvers ++= Dependencies.Resolvers)
  .settings(scalastyleFailOnWarning := true)
  .settings(libraryDependencies ++= Config.All)
  .settings(libraryDependencies ++= Circe.All)
  .settings(
    libraryDependencies ++= Seq(
      Akka.Actor,
      Akka.ActorTyped,
      Akka.Slf4j,
      Akka.Stream,
      Akka.StreamTyped,
      Akka.Http,
      Akka.AkkaStreamKafka,
      Avro.Avro4sKafka,
      Kafka.AvroSerializer,
      Kafka.Kafka,
      Kafka.Clients,
      Kafka.MonitoringInterceptors,
      Logging.Logback,
      Logging.Log4jOverSlf4j         % Test,
      Logging.JulToSlf4j             % Test,
      Testing.ScalaTest              % Test,
      Testing.EmbeddedKafka          % Test,
      Testing.EmbeddedSchemaRegistry % Test,
      Testing.AkkaTestKit            % Test,
      Testing.AkkaTypedTestKit       % Test,
      Testing.AkkaHttpTestKit        % Test,
      Testing.AkkaStreamTestKit      % Test,
      Testing.AkkaStreamKafkaTestKit % Test,
      Testing.Scalactic              % Test
    )
  )
  .dependsOn(avro)

lazy val gatling = (project in file("gatling"))
  .enablePlugins(GatlingPlugin)
  .settings(BaseSettings: _*)
  .settings(libraryDependencies ++= GatlingDeps.All)
  .dependsOn(avro)
