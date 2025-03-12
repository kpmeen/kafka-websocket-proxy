// scalastyle:off
logLevel := Level.Warn

resolvers ++= DefaultOptions.resolvers(snapshot = false)
resolvers ++= Resolver.sonatypeOssRepos("releases")
resolvers ++= Seq(
  Resolver.mavenCentral,
  Resolver.jcenterRepo,
  Resolver.typesafeRepo("releases"),
  Resolver.sbtIvyRepo("releases"),
  // Remove below resolver once the following issues has been resolved:
  // https://issues.jboss.org/projects/JBINTER/issues/JBINTER-21
  "JBoss" at "https://repository.jboss.org/"
)

// sbt-git plugin to get access to git commands from the build scripts
addSbtPlugin("com.github.sbt" % "sbt-git" % "2.1.0")

// Dependency handling
addSbtPlugin("com.timushev.sbt" % "sbt-updates" % "0.6.4")

// Formatting and style checking
addSbtPlugin("org.scalameta"       % "sbt-scalafmt"   % "2.5.4")
addSbtPlugin("com.beautiful-scala" % "sbt-scalastyle" % "1.5.1")

// Code coverage
addSbtPlugin("org.scoverage" %% "sbt-scoverage" % "2.3.0")

// Native packaging plugin
addSbtPlugin("com.github.sbt" %% "sbt-native-packager" % "1.11.1")

// Release plugin
addSbtPlugin("com.github.sbt" % "sbt-release" % "1.4.0")

// Gatling plugin
//addSbtPlugin("io.gatling" % "gatling-sbt" % "3.1.0")

//classpathTypes += "maven-plugin"

// Scalafix for automated code re-writes when updating dependencies
addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.14.0")

// documentation generator
addSbtPlugin("org.scalameta" % "sbt-mdoc" % "2.6.4")

// FIXME: Workaround to get project working due to scala-xml conflicts.
//        The culprit is a transitive dependency to scalariform_2.12 that relies
//        on scala-xml 1.2.0. While everything else depends on 2.1.0
//        see: https://stackoverflow.com/a/74338012
ThisBuild / libraryDependencySchemes += "org.scala-lang.modules" %% "scala-xml" % VersionScheme.Always
