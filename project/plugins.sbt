// scalastyle:off
logLevel := Level.Warn

resolvers ++= DefaultOptions.resolvers(snapshot = false)
resolvers ++= Seq(
  Resolver.typesafeRepo("releases"),
  Resolver.sonatypeRepo("releases"),
  // Remove below resolver once the following issues has been resolved:
  // https://issues.jboss.org/projects/JBINTER/issues/JBINTER-21
  "JBoss" at "https://repository.jboss.org/"
)

// Dependency handling
addSbtPlugin("com.timushev.sbt" % "sbt-updates" % "0.4.2")

// Formatting and style checking
addSbtPlugin("org.scalameta"  % "sbt-scalafmt"           % "2.0.4")
addSbtPlugin("org.scalastyle" %% "scalastyle-sbt-plugin" % "1.0.0")

// Code coverage
addSbtPlugin("org.scoverage" %% "sbt-scoverage" % "1.6.0")

// Native packaging plugin
addSbtPlugin("com.typesafe.sbt" %% "sbt-native-packager" % "1.4.1")

// Release plugin
addSbtPlugin("com.github.gseitz" % "sbt-release" % "1.0.11")

// Gatling plugin
addSbtPlugin("io.gatling" % "gatling-sbt" % "3.0.0")
