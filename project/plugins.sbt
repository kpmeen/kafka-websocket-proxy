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
addSbtPlugin("io.get-coursier"  %% "sbt-coursier" % "1.0.2")
addSbtPlugin("com.timushev.sbt" % "sbt-updates"   % "0.3.4")

// Formatting and style checking
addSbtPlugin("com.geirsson"   % "sbt-scalafmt"           % "1.5.1")
addSbtPlugin("org.scalastyle" %% "scalastyle-sbt-plugin" % "1.0.0")

// Code coverage
addSbtPlugin("org.scoverage" %% "sbt-scoverage"       % "1.5.1")
addSbtPlugin("com.codacy"    %% "sbt-codacy-coverage" % "1.3.12")

// Native packaging plugin
addSbtPlugin("com.typesafe.sbt" %% "sbt-native-packager" % "1.3.5")

// Gatling plugin
addSbtPlugin("io.gatling" % "gatling-sbt" % "3.0.0")
