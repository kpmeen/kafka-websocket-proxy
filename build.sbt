import Dependencies._
import Settings._
import net.scalytica.sbt.plugin.{
  DockerTasksPlugin,
  ExtLibTaskPlugin,
  PrometheusConfigPlugin
}
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
  pushChanges // : ReleaseStep, also checks that an upstream branch is properly configured
)

Global / excludeLintKeys ++= Set(
  releaseProcess,
  packageDoc / publishArtifact,
  server / externalJars,
  server / dockerRepository
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
    coverageExcludedPackages :=
      """<empty>;net\.scalytica\.kafka\.wsproxy\.avro.*"""
  )
  .settings(libraryDependencies ++= Avro.All)
  .settings(libraryDependencies += Testing.ScalaTest % Test)
  .settings(libraryDependencies += Logging.Slf4jNop % Test)
  .settings(dependencyOverrides ++= Overrides.Deps: _*)

lazy val server = (project in file("server"))
  .enablePlugins(
    ExtLibTaskPlugin,
    JavaServerAppPackaging,
    DockerPlugin,
    PrometheusConfigPlugin
  )
  .settings(externalJars := Dependencies.Monitoring.All)
  .settings(NoPublish)
  .settings(BaseSettings: _*)
  .settings(dockerSettings(8078))
  .settings(scalastyleFailOnWarning := true)
  .settings(
    coverageExcludedPackages :=
      "<empty>" +
        """;net\.scalytica\.kafka\.wsproxy\.*ServerBindings""" +
        """;net\.scalytica\.kafka\.wsproxy\.*Server""" +
        """;net\.scalytica\.kafka\.wsproxy\.Configuration\..*Cfg""" +
        """;net\.scalytica\.kafka\.wsproxy\.*LoggerExtensions""" +
        """;net\.scalytica\.kafka\.wsproxy\.errors\..*""" +
        """;net\.scalytica\.kafka\.wsproxy\.auth\..*OpenIdConnectConfig.*"""
  )
  .settings(libraryDependencies ++= Config.All)
  .settings(libraryDependencies ++= Circe.All)
  .settings(libraryDependencies ++= Logging.All)
  .settings(libraryDependencies ++= OAuth.All)
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
      Kafka.Clients,
      ConfluentKafka.AvroSerializer,
      ConfluentKafka.MonitoringInterceptors,
      Logging.Log4jOverSlf4j         % Test,
      Logging.JulToSlf4j             % Test,
      Testing.ScalaTest              % Test,
      Testing.EmbeddedSchemaRegistry % Test,
      Testing.AkkaTestKit            % Test,
      Testing.AkkaTypedTestKit       % Test,
      Testing.AkkaHttpTestKit        % Test,
      Testing.AkkaStreamTestKit      % Test,
      Testing.AkkaStreamKafkaTestKit % Test,
      Testing.Scalactic              % Test
    )
  )
  .settings(dependencyOverrides ++= Overrides.Deps: _*)
  .dependsOn(avro)

lazy val docs = (project in file("kafka-websocket-proxy-docs"))
  .enablePlugins(MdocPlugin, DocusaurusPlugin)
  .settings(
    organization := "net.scalytica",
    scalaVersion := Versions.ScalaVersion,
    moduleName   := "kafka-websocket-proxy-docs",
    mdocVariables := Map(
      "VERSION" -> version.value
    )
  )
