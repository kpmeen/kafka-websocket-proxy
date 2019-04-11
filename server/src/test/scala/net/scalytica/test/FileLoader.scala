package net.scalytica.test

import java.net.URI
import java.nio.file.FileSystems.{getFileSystem, newFileSystem}
import java.nio.file.{Path, Paths}

import akka.stream.IOResult
import akka.stream.scaladsl.{FileIO, Source}
import akka.util.ByteString
import com.typesafe.config.{Config, ConfigFactory}

import scala.collection.JavaConverters._
import scala.concurrent.Future
import scala.util.Try

trait FileLoader {

  def fileSource(p: Path): Source[ByteString, Future[IOResult]] =
    FileIO.fromPath(p)

  def loadFile(f: String): Path = {
    Paths.get(getClass.getResource(f).toURI)
  }

  def loadConfig(f: String = "application.conf"): Config =
    ConfigFactory.load(f)

  def filePath(f: String): Path = {
    val fileUrl = getClass.getResource(f)
    val thePath = {
      // Check if we're referencing a file inside a JAR file.
      // If yes, we need (for some reason) to handle that explicitly by
      // defining a FileSystem instance that understands how to load classpath
      // resources inside a JAR file.
      if (fileUrl.toString.contains("!")) {
        val arr = fileUrl.toString.split("!")
        val uri = URI.create(arr(0))
        val fileSystem = Try(getFileSystem(uri)).toEither match {
          case Left(_)   => newFileSystem(uri, Map.empty[String, String].asJava)
          case Right(fs) => fs
        }
        fileSystem.getPath(arr(1))
      } else {
        Paths.get(fileUrl.toURI)
      }
    }
    thePath
  }
}

object FileLoader extends FileLoader
