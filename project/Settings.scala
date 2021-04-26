import com.typesafe.sbt.SbtGit
import com.typesafe.sbt.packager.Keys._
import com.typesafe.sbt.packager.docker.DockerPlugin.autoImport.Docker
import com.typesafe.sbt.packager.docker.{Cmd, DockerAlias}
import com.typesafe.sbt.packager.universal.UniversalPlugin.autoImport.Universal
import net.scalytica.sbt.plugin.ExtLibTaskPlugin
import net.scalytica.sbt.plugin.ExtLibTaskPlugin.autoImport._
import net.scalytica.sbt.plugin.PrometheusConfigPlugin.autoImport._
import org.scalafmt.sbt.ScalafmtPlugin.autoImport.scalafmtOnCompile
import sbt.Keys._
import sbt.TestFrameworks.ScalaTest
import sbt._

// scalastyle:off
object Settings {

  val BaseScalacOpts = Seq(
    "-encoding",
    "utf-8",         // Specify character encoding used by source files.
    "-feature",      // Emit warning and location for usages of features that should be imported explicitly.
    "-deprecation",  // Emit warning and location for usages of deprecated APIs.
    "-unchecked",    // Enable additional warnings where generated code depends on assumptions.
    "-explaintypes", // Explain type errors in more detail.
    "-Xcheckinit",   // Wrap field accessors to throw an exception on uninitialized access.
//    "-Xfatal-warnings", // Fail the compilation if there are any warnings.
    "-Xlint:adapted-args",         // Warn if an argument list is modified to match the receiver.
    "-Xlint:constant",             // Evaluation of a constant arithmetic expression results in an error.
    "-Xlint:delayedinit-select",   // Selecting member of DelayedInit.
    "-Xlint:doc-detached",         // A ScalaDoc comment appears to be detached from its element.
    "-Xlint:inaccessible",         // Warn about inaccessible types in method signatures.
    "-Xlint:infer-any",            // Warn when a type argument is inferred to be `Any`.
    "-Xlint:missing-interpolator", // A string literal appears to be missing an interpolator id.
//    "-Xlint:nullary-override",       // Warn when non-nullary `def f()' overrides nullary `def f'.
    "-Xlint:nullary-unit",           // Warn when nullary methods return Unit.
    "-Xlint:option-implicit",        // Option.apply used implicit view.
    "-Xlint:package-object-classes", // Class or object defined in package object.
    "-Xlint:poly-implicit-overload", // Parameterized overloaded implicit methods are not visible as view bounds.
    "-Xlint:private-shadow",         // A private field (or class parameter) shadows a superclass field.
    "-Xlint:stars-align",            // Pattern sequence wildcard must align with sequence component.
    "-Xlint:type-parameter-shadow",  // A local type parameter shadows a type already in scope.
    "-language:implicitConversions", // Allow implicit conversions
    "-language:experimental.macros", // Allow macro definition (besides implementation and application)
    "-language:higherKinds",         // Allow using higher-kinded types
    "-language:existentials",        // Allow using existential types
    "-language:postfixOps",          // Allow using postfix operator semantics
    "-Xsource:2.13"
  )

  val ExperimentalScalacOpts = Seq(
    "-Ywarn-dead-code",        // Warn when dead code is identified.
    "-Ywarn-value-discard",    // Warn when non-Unit expression results are unused.
    "-Ywarn-extra-implicit",   // Warn when more than one implicit parameter section is defined.
    "-Ywarn-numeric-widen",    // Warn when numerical values are widened.
    "-Ywarn-unused:implicits", // Warn if an implicit parameter is unused.
    "-Ywarn-unused:imports",   // Warn if an import selector is not referenced.
    "-Ywarn-unused:locals",    // Warn if a local definition is unused.
    "-Ywarn-unused:params",    // Warn if a value parameter is unused.
    "-Ywarn-unused:patvars",   // Warn if a variable bound in a pattern is unused.
    "-Ywarn-unused:privates"   // Warn if a private member is unused.
  )

  val BaseSettings = Seq(
    organization := "net.scalytica",
    licenses += ("Apache-2.0", url(
      "http://opensource.org/licenses/https://opensource.org/licenses/Apache-2.0"
    )),
    scalaVersion := Versions.ScalaVersion,
    scalacOptions := BaseScalacOpts ++ ExperimentalScalacOpts,
    Test / scalacOptions ++= Seq("-Yrangepos"),
    // Require compilation against JDK 11.
    javacOptions ++= Seq("-source", "11", "-target", "11"),
    javaOptions ++= Seq(
      // Set timezone to UTC
      "-Duser.timezone=UTC",
      // Enable the Graal JIT!!!
      "-XX:+UnlockExperimentalVMOptions",
      "-XX:+EnableJVMCI",
      "-XX:+UseJVMCICompiler"
    ),
    run / fork := true,
    Test / fork := true,
    Test / logBuffered := true,
    Test / testOptions ++= Seq(Tests.Argument(ScalaTest, "-oD")),
    updateOptions := updateOptions.value.withCachedResolution(true),
    // Disable ScalaDoc generation
    Compile / packageDoc / publishArtifact := false,
    packageDoc / publishArtifact := false,
    Compile / doc / sources := Seq.empty,
    turbo := true,
    resolvers ++= Dependencies.Resolvers,
    scalafmtOnCompile := true
  )

  val GitLabRegistry = "registry.gitlab.com"
  val GitLabUser     = "kpmeen"
  val DockerHubUser  = "kpmeen"

  private[this] def gitlabDockerAlias(pkg: String, ver: String): DockerAlias = {
    DockerAlias(
      registryHost = Some(GitLabRegistry),
      username = Some(GitLabUser),
      name = s"kafka-websocket-proxy/$pkg",
      tag = Some(ver)
    )
  }

  private[this] def dockerhubAlias(version: String): DockerAlias = {
    DockerAlias(
      registryHost = None,
      username = Option(DockerHubUser),
      name = "kafka-websocket-proxy",
      Option(version)
    )
  }

  def dockerSettings(exposedPort: Int) =
    Seq(
      maintainer := "contact@scalytica.net",
      dockerRepository := Some(s"$GitLabRegistry/$GitLabUser"),
      dockerAlias := gitlabDockerAlias(packageName.value, version.value),
      dockerAliases ++= {
        val commitSha = SbtGit.git.gitHeadCommit.value
        val gitLab    = dockerAlias.value
        val dockerHub = dockerhubAlias(version.value)

        if (dockerUpdateLatest.value) {
          // Push to both the GitLab and DockerHub registries.
          Seq(
            gitLab,
            gitLab.withTag(commitSha),
            gitLab.withTag(Option("latest")),
            dockerHub,
            dockerHub.withTag(Option("latest"))
          )
        } else {
          // Snapshots are only pushed to the GitLab registry.
          Seq(
            gitLab,
            gitLab.withTag(commitSha)
          )
        }
      },
      dockerUpdateLatest := !isSnapshot.value,
      dockerBaseImage := "azul/zulu-openjdk-debian:11",
      Docker / dockerExposedPorts := Seq(exposedPort),
      dockerCommands := {
        val (front, back) = dockerCommands.value.splitAt(12)
        val aptCmd = Cmd(
          "RUN",
          "apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y apt-utils curl"
        )
        front ++ Seq(aptCmd) ++ back
      },
      //----------------------------------------------------
      // Extra file mappings to external libs and configs
      //----------------------------------------------------
      prometheusConfigFileTargetDir := target.value / externalLibsDirName.value,
      Docker / stage := (
        (Docker / stage) dependsOn (prometheusGenerate dependsOn retrieveExtLibs)
      ).value,
      Universal / mappings ++= {
        externalJarFiles.value.map { ejf =>
          ejf -> s"${externalLibsDirName.value}/${ejf.name}"
        } ++ Seq(
          prometheusConfigFile.value -> s"${externalLibsDirName.value}/${prometheusConfigFileName.value}"
        )
      },
      //----------------------------------------------------
      // enable JMX
      //----------------------------------------------------
      bashScriptExtraDefines += {
        """addJava "-Dcom.sun.management.jmxremote """ +
          """-Dcom.sun.management.jmxremote.authenticate=false """ +
          """-Dcom.sun.management.jmxremote.ssl=false """ +
          """-Dcom.sun.management.jmxremote.port=7776 """ +
          """-Dcom.sun.management.jmxremote.local.only=true""""
      },
      //----------------------------------------------------
      // Jolokia
      //----------------------------------------------------
      bashScriptExtraDefines ++= Seq(
        """declare -a jolokia_enabled""",
        """declare -a jolokia_config""",
        "",
        """if [[ "$ENABLE_JOLOKIA" == "true" ]]; then""",
        """    jolokia_enabled=true;""",
        """else""",
        """    jolokia_enabled=false;""",
        """fi""",
        """# See: https://jolokia.org/reference/html/agents.html#agent-jvm-config""",
        """if [[ -z "$JOLOKIA_CONFIG" ]]; then""",
        """    jolokia_config="port=7777,host=localhost";""",
        """else""",
        """    jolokia_config=$JOLOKIA_CONFIG;""",
        """fi""",
        ""
      ),
      bashScriptExtraDefines ++= {
        val ld =
          s"${(Docker / defaultLinuxInstallLocation).value}/${externalLibsDirName.value}"
        val jolokiaJar = ExtLibTaskPlugin.moduleIdToDestFileName(
          Dependencies.Monitoring.JolokiaAgent
        )
        Seq(
          """if [[ "$jolokia_enabled" == "true" ]]; then """,
          s"""    addJava "-javaagent:$ld/$jolokiaJar=""" + """$jolokia_config";""",
          """fi""",
          ""
        )
      },
      //----------------------------------------------------
      // Prometheus JMX exporter
      //----------------------------------------------------
      bashScriptExtraDefines ++= {
        val promCfg =
          s"${(Docker / defaultLinuxInstallLocation).value}/" +
            s"${externalLibsDirName.value}/" +
            s"${prometheusConfigFileName.value}"
        Seq(
          """declare -a prometheus_exporter_enabled""",
          """declare -a prometheus_exporter_port""",
          """declare -a prometheus_exporter_config""",
          "",
          """if [[ "$ENABLE_PROMETHEUS_EXPORTER" == "true" ]]; then""",
          """    prometheus_exporter_enabled=true;""",
          """else""",
          """    prometheus_exporter_enabled=false;""",
          """fi""",
          """if [[ -z "$PROMETHEUS_EXPORTER_PORT" ]]; then""",
          """    prometheus_exporter_port="7778";""",
          """else""",
          """    prometheus_exporter_port=$PROMETHEUS_EXPORTER_PORT;""",
          """fi""",
          """if [[ -z "$PROMETHEUS_EXPORTER_CONFIG" ]]; then""",
          s"""    prometheus_exporter_config=$promCfg;""",
          """else""",
          """    prometheus_exporter_config=$PROMETHEUS_EXPORTER_CONFIG;""",
          """fi"""
        )
      },
      bashScriptExtraDefines ++= {
        val ld =
          s"${(Docker / defaultLinuxInstallLocation).value}/${externalLibsDirName.value}"
        val promJar = ExtLibTaskPlugin.moduleIdToDestFileName(
          Dependencies.Monitoring.PrometheusAgent
        )
        val prometheusJar = s"$ld/$promJar"
        Seq(
          """if [[ "$prometheus_exporter_enabled" == "true" ]]; then """,
          s"""    prometheus_arg="-javaagent:$prometheusJar""" + """=$prometheus_exporter_port:$prometheus_exporter_config";""",
          """     echo "Setting prometheus agent arg: $prometheus_arg"""",
          """     addJava $prometheus_arg""",
          """fi""",
          ""
        )
      }
    )

  val NoPublish = Seq(
    publishLocal := {},
    publish := {}
  )

}

// scalastyle:on
