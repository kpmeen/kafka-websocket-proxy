package net.scalytica.sbt.plugin

import java.nio.charset.StandardCharsets

import com.typesafe.sbt.packager.docker.DockerPlugin
import sbt.Keys._
import sbt._

object PrometheusConfigPlugin extends AutoPlugin {

  override def requires = DockerPlugin

  override def trigger = AllRequirements

  object autoImport {

    val prometheusConfigFileName =
      settingKey[String]("The name of the prometheus config file")

    val prometheusConfigFileTargetDir =
      settingKey[File]("The directory to save the generated prometheus config.")

    val prometheusConfigFile = settingKey[File]("The generated config file")

    val prometheusGenerate =
      taskKey[Unit]("Generate the default prometheus-config.yml")

  }

  val DefaultConfig =
    """---
      |
      |startDelaySeconds: 0
      |lowercaseOutputName: false
      |lowercaseOutputLabelNames: false
      |""".stripMargin

  import autoImport._

  private[this] def writeConfig(destFile: File): Unit = {
    IO.write(
      file = destFile,
      content = DefaultConfig,
      charset = StandardCharsets.UTF_8,
      append = false
    )
  }

  override def projectSettings = {
    Seq(
      prometheusConfigFileName      := "prometheus-config.yml",
      prometheusConfigFileTargetDir := target.value,
      prometheusConfigFile := {
        prometheusConfigFileTargetDir.value / prometheusConfigFileName.value
      },
      prometheusGenerate := writeConfig(prometheusConfigFile.value)
    )
  }

}
