// scalastyle:off
logLevel := Level.Warn

resolvers ++= DefaultOptions.resolvers(snapshot = false)
resolvers ++= Resolver.sonatypeOssRepos("releases")
resolvers ++= Seq(
  Resolver.typesafeRepo("releases"),
  // Remove below resolver once the following issues has been resolved:
  // https://issues.jboss.org/projects/JBINTER/issues/JBINTER-21
  "JBoss" at "https://repository.jboss.org/"
)

// sbt-git plugin to get access to git commands from the build scripts
addSbtPlugin("com.github.sbt" % "sbt-git" % "2.0.1")

// Dependency handling
addSbtPlugin("com.timushev.sbt" % "sbt-updates" % "0.6.4")

// Formatting and style checking
addSbtPlugin("org.scalameta"       % "sbt-scalafmt"   % "2.5.0")
addSbtPlugin("com.beautiful-scala" % "sbt-scalastyle" % "1.5.1")

// Code coverage
addSbtPlugin("org.scoverage" %% "sbt-scoverage" % "2.0.7")

// Native packaging plugin
addSbtPlugin("com.github.sbt" %% "sbt-native-packager" % "1.9.16")

// Release plugin
addSbtPlugin("com.github.sbt" % "sbt-release" % "1.1.0")

// Gatling plugin
//addSbtPlugin("io.gatling" % "gatling-sbt" % "3.1.0")

//classpathTypes += "maven-plugin"

// Scalafix for automated code re-writes when updating dependencies
addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.10.4")

// documentation generator
addSbtPlugin("org.scalameta" % "sbt-mdoc" % "2.3.7")

// FIXME: Workaround to get project working due to scala-xml conflicts.
//        The culprit is a transitive dependency to scalariform_2.12 that relies
//        on scala-xml 1.2.0. While everything else depends on 2.1.0
//        see: https://stackoverflow.com/a/74338012
ThisBuild / libraryDependencySchemes += "org.scala-lang.modules" %% "scala-xml" % VersionScheme.Always
