package net.scalytica.sbt.plugin

import sbt._

import scala.sys.process._

object DockerTasksPlugin extends AutoPlugin {

  object autoImport {
    lazy val startKafka   = taskKey[Unit]("Start single-node cluster.")
    lazy val stopKafka    = taskKey[Unit]("Stop single-node cluster.")
    lazy val restartKafka = taskKey[Unit]("Restart single-node cluster.")
    lazy val statusKafka  = taskKey[Unit]("Check single-node cluster status.")

  }

  import autoImport._

  override def globalSettings: Seq[Setting[_]] = Seq(
    startKafka := { "./docker/single-node/init.sh start" ! },
    stopKafka := { "./docker/single-node/init.sh stop" ! },
    restartKafka := { "./docker/single-node/init.sh restart" ! },
    statusKafka := { "./docker/single-node/init.sh status" ! }
  )

}
