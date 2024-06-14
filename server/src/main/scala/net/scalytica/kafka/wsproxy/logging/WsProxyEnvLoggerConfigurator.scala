package net.scalytica.kafka.wsproxy.logging

import java.io.ByteArrayInputStream
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.classic.{Level, Logger => LogbackLogger}
import net.scalytica.kafka.wsproxy.StringExtensions
import net.scalytica.kafka.wsproxy.utils.{WsProxyEnvLoader => env}
import org.slf4j.{ILoggerFactory, LoggerFactory}

trait WsProxyEnvLoggerConfigurator {

  val RootLogger    = "ROOT"
  val PropPrefix    = "logger."
  val LogbackCfgEnv = "WSPROXY_LOGBACK_XML_CONFIG"

  private[this] lazy val loggerFactory: ILoggerFactory =
    LoggerFactory.getILoggerFactory

  private[this] val log =
    loggerFactory.getLogger(getClass.getCanonicalName)

  private[this] lazy val ctx = loggerFactory
    .getLogger(RootLogger)
    .asInstanceOf[LogbackLogger]
    .getLoggerContext

  private[this] def createJoranCfg(): JoranConfigurator = {
    val c = new JoranConfigurator
    c.setContext(ctx)
    currentConfigurator = Some(c)
    c
  }

  private var currentConfigurator: Option[JoranConfigurator] = None

  private[this] def configurator(reuse: Boolean): JoranConfigurator = {
    log.trace("Initialising logback configurator...")
    currentConfigurator
      .map(curr => if (!reuse) createJoranCfg() else curr)
      .getOrElse(createJoranCfg())
  }

  def load(): Unit = {
    log.trace("Checking environment for logback configurations...")
    if (!env.hasKey(LogbackCfgEnv)) loadLoggersFromEnv()
    else loadConfigStringFromEnv()
  }

  def reload(): Unit = {
    log.trace("Reloading logback...")
    reset()
    load()
  }

  private[logging] def reset(): Unit = {
    log.trace("Resetting logback context...")
    configurator(true).setContext(ctx)
  }

  private[this] def loadLoggersFromEnv(): Unit = {
    log.trace("Loading loggers from environment...")
    val loggers = env.properties.view.filterKeys(_.startsWith(PropPrefix)).toMap
    loggers
      .map(kv => kv._1.stripPrefix(PropPrefix) -> Level.toLevel(kv._2))
      .foreach(kv => setLoggerLevel(kv._1, kv._2))
  }

  private[this] def loadConfigStringFromEnv(): Unit = {
    env.properties
      .find(p => p._1.equals(LogbackCfgEnv) && p._2.safeNonEmpty)
      .foreach(kv => loadConfigString(kv._2))
  }

  private[this] def setLoggerLevel(loggerName: String, level: Level): Unit =
    loggerFactory.synchronized {
      loggerFactory
        .getLogger(loggerName)
        .asInstanceOf[LogbackLogger]
        .setLevel(level)
    }

  private[logging] def loadConfigString(cfg: String): Unit =
    loggerFactory.synchronized {
      ctx.reset()
      val is = new ByteArrayInputStream(cfg.getBytes)
      configurator(false).doConfigure(is)
    }

}

object WsProxyEnvLoggerConfigurator extends WsProxyEnvLoggerConfigurator
