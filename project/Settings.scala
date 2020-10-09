import com.typesafe.sbt.SbtGit
import com.typesafe.sbt.packager.Keys._
import com.typesafe.sbt.packager.docker.{Cmd, DockerAlias}
import com.typesafe.sbt.packager.docker.DockerPlugin.autoImport.Docker
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
    scalacOptions in Test ++= Seq("-Yrangepos"),
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
    publishArtifact in (Compile, packageDoc) := false,
    packageDoc / publishArtifact := false,
    sources in (Compile, doc) := Seq.empty,
    turbo := true,
    resolvers ++= Dependencies.Resolvers,
    scalafmtOnCompile := true
  )

  val GitLabRegistry = "registry.gitlab.com"
  val GitLabUser     = "kpmeen"
  val DockerHubUser  = "kpmeen"

  def dockerSettings(exposedPort: Int) =
    Seq(
      maintainer := "contact@scalytica.net",
      dockerRepository := Some(s"$GitLabRegistry/$GitLabUser"),
      dockerAlias := {
        DockerAlias(
          registryHost = Some(GitLabRegistry),
          username = Some(GitLabUser),
          name = s"kafka-websocket-proxy/${packageName.value}",
          tag = Some(version.value)
        )
      },
      dockerAliases ++= {
        val commitSha = SbtGit.git.gitHeadCommit.value
        val gitLab    = dockerAlias.value
        val dockerHub = DockerAlias(
          registryHost = None,
          username = Option(DockerHubUser),
          name = "kafka-websocket-proxy",
          Option(version.value)
        )

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
      dockerExposedPorts in Docker := Seq(exposedPort),
      dockerCommands := {
        val (front, back) = dockerCommands.value.splitAt(12)
        val cmd = Cmd(
          "RUN",
          "apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y apt-utils curl"
        )
        front ++ Seq(cmd) ++ back
      }
    )

  val NoPublish = Seq(
    publish := {},
    publishLocal := {}
  )

}

// scalastyle:on
