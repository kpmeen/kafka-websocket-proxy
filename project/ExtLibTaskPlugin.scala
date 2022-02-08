package net.scalytica.sbt.plugin

import com.typesafe.sbt.packager.docker.DockerPlugin
import lmcoursier.{CoursierConfiguration, CoursierDependencyResolution}
import sbt.Keys._
import sbt._
import sbt.internal.CustomHttp
import sbt.internal.util.ManagedLogger
import sbt.librarymanagement.DependencyResolution
import sbt.librarymanagement.ivy.IvyDependencyResolution

object ExtLibTaskPlugin extends AutoPlugin {

  override def requires = DockerPlugin

//  override def trigger = AllRequirements

  object autoImport {

    lazy val externalLibsDirName = settingKey[String](
      "The directory name to download jars into. Directory is relative to " +
        "the base directory."
    )

    lazy val externalLibsDir = settingKey[File](
      "The full file path where the downloaded jars are placed"
    )

    lazy val createExternalLibsDir = taskKey[File](
      "Creates the external libs dir"
    )

    lazy val externalJars = settingKey[Seq[ModuleID]](
      "Jar files to download into the external libraries folder"
    )

    lazy val externalJarNames = settingKey[Seq[String]](
      "The final name of the jar file at its destination"
    )

    lazy val externalJarFiles =
      settingKey[Seq[File]]("The downloaded external jar files")

    lazy val retrieveExtLibs =
      taskKey[Unit]("Download jar files and copy to ext dir")

    lazy val depResSetting =
      settingKey[DependencyResolution]("The dependency resolution mechanism")
  }

  import autoImport._

  private[this] val csrCfg = CoursierConfiguration()
  private[this] val ivyCfg = InlineIvyConfiguration()

  def moduleIdToDestFileName(mid: ModuleID): String = {
    s"${mid.name}.jar"
  }

  def moduleIdToDestFile(mid: ModuleID, dest: File): File = {
    dest / moduleIdToDestFileName(mid)
  }

  private[this] def verifyExtLibsTargetDir(
      extLibsDir: File,
      logger: ManagedLogger
  ): Unit = {
    logger.info(s"Verifying $extLibsDir...")
    if (!extLibsDir.exists()) {
      logger.info(s"Creating directory $extLibsDir...")
      IO.createDirectory(extLibsDir)
    } else {
      logger.info(s"Directory $extLibsDir already exists...")
    }
  }

  private[this] def retrieveExtJars(
      extLibsDir: File,
      moduleIds: Seq[ModuleID],
      depRes: DependencyResolution,
      logger: ManagedLogger
  ): Unit = {
    logger.info(
      s"Will try to retrieve jars for: ${moduleIds.mkString("\n", "\n", "")}"
    )
    moduleIds.foreach { mid =>
      val destFileName = moduleIdToDestFile(mid, extLibsDir)
      val retRes       = depRes.retrieve(mid, None, extLibsDir, logger)

      retRes match {
        case Right(f) =>
          logger.info(s"Downloaded files: ${f.map(_.getName).mkString(", ")}")
          f.find(
            _.name.startsWith(s"${mid.name}-${mid.revision}")
          ).foreach { f =>
            IO.copyFile(f, destFileName)
            logger.info(s"Copied $f to $destFileName")
          }

        case Left(err) =>
          logger.info(
            s"Could not retrieve jar file for $mid due to " +
              s"${err.resolveException.getMessage}"
          )
      }
    }
  }

  def retrieveExtJarsState(
      extLibsDir: File,
      moduleIds: Seq[ModuleID],
      depRes: DependencyResolution
  )(state: State): State = {
    verifyExtLibsTargetDir(extLibsDir, state.log)
    state.log.info("Retrieving JAR files...")
    retrieveExtJars(extLibsDir, moduleIds, depRes, state.log)
    state
  }

  override def projectSettings = {
    Seq(
      externalLibsDirName := "ext",
      externalLibsDir     := target.value / externalLibsDirName.value,
      createExternalLibsDir := {
        val log = streams.value.log
        val dir = externalLibsDir.value
        verifyExtLibsTargetDir(dir, log)
        dir
      },
      externalJars     := Seq.empty,
      externalJarNames := externalJars.value.map(moduleIdToDestFileName),
      externalJarFiles := externalJars.value.map { ej =>
        moduleIdToDestFile(ej, externalLibsDir.value)
      },
      retrieveExtLibs := {
        val log = streams.value.log
        log.debug("Here I am big boi")
        val _  = createExternalLibsDir.value
        val dn = externalLibsDir.value
        val ej = externalJars.value
        val dr = Defaults.dependencyResolutionTask.value
        if (ej.nonEmpty) retrieveExtJars(dn, ej, dr, log)
      },
      depResSetting := Def
        .setting[DependencyResolution] {
          if (useCoursier.value) {
            CoursierDependencyResolution(csrCfg)
          } else {
            IvyDependencyResolution(ivyCfg, CustomHttp.okhttpClient.value)
          }
        }
        .value
    )
  }

}
