package net.scalytica.sbt.plugin

import sbt._

import scala.sys.process._

object DockerTasksPlugin extends AutoPlugin {

  object autoImport {
    lazy val startKafka   = taskKey[Unit]("Start single-node cluster.")
    lazy val stopKafka    = taskKey[Unit]("Stop single-node cluster.")
    lazy val restartKafka = taskKey[Unit]("Restart single-node cluster.")
    lazy val statusKafka  = taskKey[Unit]("Check single-node cluster status.")
    lazy val cleanKafka   = settingKey[Boolean]("If true, will pass -c to init")

  }

  import autoImport._

  override def globalSettings: Seq[Setting[_]] = {
    def initScript(cmd: String, clean: Boolean) = {
      val args = if (clean) " -c" else ""
      s"./docker/plain/init.sh $cmd$args"
    }

    Seq(
      cleanKafka   := true,
      startKafka   := { initScript("start", cleanKafka.value) ! },
      stopKafka    := { initScript("stop", cleanKafka.value) ! },
      restartKafka := { initScript("restart", cleanKafka.value) ! },
      statusKafka  := { initScript("status", cleanKafka.value) ! }
    )
  }

}
