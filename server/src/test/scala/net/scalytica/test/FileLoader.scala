package net.scalytica.test

import java.net.URI
import java.nio.charset.Charset
import java.nio.file.FileSystems.{getFileSystem, newFileSystem}
import java.nio.file.{Path, Paths}

import org.apache.pekko.stream.{IOResult, Materializer}
import org.apache.pekko.stream.scaladsl.{FileIO, Source}
import org.apache.pekko.util.ByteString
import com.typesafe.config.{Config, ConfigFactory}

import scala.jdk.CollectionConverters._
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.util.Try

trait FileLoader { self =>

  def fileSource(p: Path): Source[ByteString, Future[IOResult]] =
    FileIO.fromPath(p)

  def loadFile(f: String): Path = Paths.get(self.getClass.getResource(f).toURI)

  def loadConfig(f: String = "/application.conf"): Config =
    ConfigFactory.parseFile(filePath(f).toFile).resolve()

  def testConfigPath: Path = filePath("/application-test.conf")

  private[this] def loadLogbackTestConfig(
      fileName: String
  )(implicit mat: Materializer): String =
    Await.result(
      FileIO.fromPath(filePath(s"/$fileName")).runFold("") { case (str, bs) =>
        str + bs.decodeString(Charset.forName("UTF-8"))
      },
      2 seconds
    )

  def testLogbackConfig(implicit mat: Materializer): String =
    loadLogbackTestConfig("logback-test.xml")

  def logbackConfigForTestcase(testCase: Int = 1)(
      implicit mat: Materializer
  ): String =
    loadLogbackTestConfig(s"logback-config-testcase-$testCase.xml")

  def filePath(f: String): Path = {
    val fileUrl = self.getClass.getResource(f)
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
