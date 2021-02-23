package net.scalytica.kafka.wsproxy.logging

import net.scalytica.kafka.wsproxy.NiceClassNameExtensions

import com.typesafe.scalalogging.Logger

/** Convenience trait for providing loggers to different objects and classes. */
trait WithProxyLogger { self =>

  protected lazy val loggerName = self.niceClassName

  final protected lazy val _log = Logger(loggerName)

  final protected lazy val logger = _log
}

object DefaultProxyLogger extends WithProxyLogger {

  override protected lazy val loggerName =
    this.packageName.stripSuffix("logging") + "Server"

  def trace(msg: String): Unit                   = logger.trace(msg)
  def debug(msg: String): Unit                   = logger.debug(msg)
  def info(msg: String): Unit                    = logger.info(msg)
  def warn(msg: String): Unit                    = logger.warn(msg)
  def warn(msg: String, cause: Throwable): Unit  = logger.warn(msg, cause)
  def error(msg: String): Unit                   = logger.error(msg)
  def error(msg: String, cause: Throwable): Unit = logger.error(msg, cause)

}
