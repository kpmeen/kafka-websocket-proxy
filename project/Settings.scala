import sbt.Keys._
import sbt._
import com.typesafe.sbt.packager.Keys._
import com.typesafe.sbt.packager.docker.DockerAlias
import com.typesafe.sbt.packager.docker.DockerPlugin.autoImport.Docker

// scalastyle:off
object Settings {

  val BaseScalacOpts = Seq(
    "-encoding",
    "utf-8", // Specify character encoding used by source files.
    "-feature", // Emit warning and location for usages of features that should be imported explicitly.
    "-deprecation", // Emit warning and location for usages of deprecated APIs.
    "-unchecked", // Enable additional warnings where generated code depends on assumptions.
    "-explaintypes", // Explain type errors in more detail.
    "-Xfuture", // Turn on future language features.
    "-Xcheckinit", // Wrap field accessors to throw an exception on uninitialized access.
    "-Xfatal-warnings", // Fail the compilation if there are any warnings.
    "-Xlint:adapted-args", // Warn if an argument list is modified to match the receiver.
    "-Xlint:by-name-right-associative", // By-name parameter of right associative operator.
    "-Xlint:constant", // Evaluation of a constant arithmetic expression results in an error.
    "-Xlint:delayedinit-select", // Selecting member of DelayedInit.
    "-Xlint:doc-detached", // A ScalaDoc comment appears to be detached from its element.
    "-Xlint:inaccessible", // Warn about inaccessible types in method signatures.
    "-Xlint:infer-any", // Warn when a type argument is inferred to be `Any`.
    "-Xlint:missing-interpolator", // A string literal appears to be missing an interpolator id.
    "-Xlint:nullary-override", // Warn when non-nullary `def f()' overrides nullary `def f'.
    "-Xlint:nullary-unit", // Warn when nullary methods return Unit.
    "-Xlint:option-implicit", // Option.apply used implicit view.
    "-Xlint:package-object-classes", // Class or object defined in package object.
    "-Xlint:poly-implicit-overload", // Parameterized overloaded implicit methods are not visible as view bounds.
    "-Xlint:private-shadow", // A private field (or class parameter) shadows a superclass field.
    "-Xlint:stars-align", // Pattern sequence wildcard must align with sequence component.
    "-Xlint:type-parameter-shadow", // A local type parameter shadows a type already in scope.
    "-Xlint:unsound-match", // Pattern match may not be type-safe.
    "-language:implicitConversions",
    "-language:experimental.macros", // Allow macro definition (besides implementation and application)
    "-language:higherKinds",
    "-language:existentials",
    "-language:postfixOps"
  )

  val ExperimentalScalacOpts = Seq(
    "-Yno-adapted-args", // Do not adapt an argument list (either by inserting () or creating a tuple) to match the receiver.
    "-Ypartial-unification", // Enable partial unification in type constructor inference
    "-Ywarn-dead-code", // Warn when dead code is identified.
    "-Ywarn-value-discard", // Warn when non-Unit expression results are unused.
    "-Ywarn-extra-implicit", // Warn when more than one implicit parameter section is defined.
    "-Ywarn-numeric-widen", // Warn when numerical values are widened.
    "-Ywarn-unused:implicits", // Warn if an implicit parameter is unused.
    "-Ywarn-unused:imports", // Warn if an import selector is not referenced.
    "-Ywarn-unused:locals", // Warn if a local definition is unused.
    "-Ywarn-unused:params", // Warn if a value parameter is unused.
    "-Ywarn-unused:patvars", // Warn if a variable bound in a pattern is unused.
    "-Ywarn-unused:privates" // Warn if a private member is unused.
  )

  val BaseSettings = Seq(
    organization := "net.scalytica",
    licenses += ("Apache-2.0", url(
      "http://opensource.org/licenses/https://opensource.org/licenses/Apache-2.0"
    )),
    scalaVersion := Versions.ScalaVersion,
    scalacOptions := BaseScalacOpts ++ ExperimentalScalacOpts,
    scalacOptions in Test ++= Seq("-Yrangepos"),
//    javacOptions ++= Seq("-source", "10", "-target", "10"), // Require compilation against JDK 10.
    javaOptions ++= Seq(
      // Set timezone to UTC
      "-Duser.timezone=UTC",
      // Enable the Graal JIT!!!
//      "-XX:+UnlockExperimentalVMOptions",
//      "-XX:+EnableJVMCI",
//      "-XX:+UseJVMCICompiler"
    ),
    javaOptions in Test += "-Dlogger.resource=logback-test.xml",
    fork in Test := true,
    logBuffered in Test := true,
    testOptions in Test ++= Seq(
      Tests.Argument(TestFrameworks.ScalaTest, "-oD"),
      Tests.Argument(TestFrameworks.ScalaTest, "-y", "org.scalatest.WordSpec"),
      Tests.Argument(TestFrameworks.ScalaTest, "-y", "org.scalatest.PropSpec")
    ),
    updateOptions := updateOptions.value.withCachedResolution(true),
    // Disable ScalaDoc generation
    publishArtifact in (Compile, packageDoc) := false,
    publishArtifact in packageDoc := false,
    sources in (Compile, doc) := Seq.empty
  )

  val GitlabRegistry = "registry.gitlab.com"
  val GitlabUser     = "kpmeen"

  def dockerSettings(exposedPort: Option[Int] = None) =
    Seq(
      maintainer in Docker := maintainer.value,
      dockerRepository := Some(s"$GitlabRegistry/$GitlabUser"),
      dockerAlias := {
        val tag = version.value
        DockerAlias(
          Some(GitlabRegistry),
          Some(GitlabUser),
          s"kafka-websocket-proxy/${packageName.value}",
          Some(tag)
        )
      },
      dockerUpdateLatest := {
        val snapshot = isSnapshot.value
        val log      = sLog.value
        if (!snapshot) {
          log.info("Building release, updating docker latest tag")
          true
        } else {
          false
        }
      },
      dockerBaseImage in Docker := "openjdk:8",
      dockerExposedPorts in Docker := exposedPort.toSeq
    )

  val NoPublish = Seq(
    publish := {},
    publishLocal := {}
  )

}

// scalastyle:on
