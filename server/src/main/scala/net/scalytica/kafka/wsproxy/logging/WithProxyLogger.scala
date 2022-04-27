package net.scalytica.kafka.wsproxy.logging

import net.scalytica.kafka.wsproxy.NiceClassNameExtensions
import com.typesafe.scalalogging.Logger

import scala.reflect.ClassTag

/** Convenience trait for providing loggers to different objects and classes. */
trait WithProxyLogger { self =>

  protected lazy val loggerName = self.niceClassName

  final protected lazy val _log = Logger(loggerName)

  final protected lazy val log = _log

}

object WithProxyLogger {

  private[this] case class ClassProxyLogger(n: String) extends WithProxyLogger {
    override protected lazy val loggerName = n
  }

  def namedLoggerFor[T](implicit ct: ClassTag[T]): Logger = {
    val cpl = ClassProxyLogger(ct.runtimeClass.niceClassName)
    cpl.log
  }

}

object DefaultProxyLogger extends WithProxyLogger {

  override protected lazy val loggerName =
    this.packageName.stripSuffix("logging") + "Server"

  def trace(msg: String): Unit                   = log.trace(msg)
  def debug(msg: String): Unit                   = log.debug(msg)
  def info(msg: String): Unit                    = log.info(msg)
  def warn(msg: String): Unit                    = log.warn(msg)
  def warn(msg: String, cause: Throwable): Unit  = log.warn(msg, cause)
  def error(msg: String): Unit                   = log.error(msg)
  def error(msg: String, cause: Throwable): Unit = log.error(msg, cause)

}
