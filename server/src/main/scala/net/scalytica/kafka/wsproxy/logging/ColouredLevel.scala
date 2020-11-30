package net.scalytica.kafka.wsproxy.logging

import ch.qos.logback.classic._
import ch.qos.logback.classic.pattern._
import ch.qos.logback.classic.spi._

class ColouredLevel extends ClassicConverter {

  def convert(event: ILoggingEvent): String = event.getLevel match {
    case Level.TRACE => Colours.blue(Level.TRACE.levelStr)
    case Level.DEBUG => Colours.cyan(Level.DEBUG.levelStr)
    case Level.INFO  => Colours.white(Level.INFO.levelStr)
    case Level.WARN  => Colours.yellow(Level.WARN.levelStr)
    case Level.ERROR => Colours.red(Level.ERROR.levelStr)
  }

}

object Colours {

  import scala.Console._

  private[this] val SbtLogNoFormat        = "sbt.log.noformat"
  private[this] val WsProxyLogNoFormat    = "wsproxy.log.noformat"
  private[this] val WsProxyLogNoFormatEnv = "WSPROXY_LOG_ANSI_OFF"

  /**
   * Check if coloured output is enabled and that the current environment
   * actually supports ANSI coloured output.
   *
   * @return
   *   true if ANSI colours should be used, otherwise false
   */
  def useANSI: Boolean =
    sys.props
      .get(SbtLogNoFormat)
      .orElse(sys.props.get(WsProxyLogNoFormat))
      .orElse(sys.env.get(WsProxyLogNoFormatEnv))
      .map(_ != "true")
      .orElse {
        sys.props
          .get("os.name")
          .map(_.toLowerCase(java.util.Locale.ENGLISH))
          .filter(_.contains("windows"))
          .map(_ => false)
      }
      .getOrElse(true)

  // Functions for adding ANSI colour codes to a String
  def red(str: String): String     = if (useANSI) s"$RED$str$RESET" else str
  def blue(str: String): String    = if (useANSI) s"$BLUE$str$RESET" else str
  def cyan(str: String): String    = if (useANSI) s"$CYAN$str$RESET" else str
  def green(str: String): String   = if (useANSI) s"$GREEN$str$RESET" else str
  def magenta(str: String): String = if (useANSI) s"$MAGENTA$str$RESET" else str
  def white(str: String): String   = if (useANSI) s"$WHITE$str$RESET" else str
  def black(str: String): String   = if (useANSI) s"$BLACK$str$RESET" else str
  def yellow(str: String): String  = if (useANSI) s"$YELLOW$str$RESET" else str

}
