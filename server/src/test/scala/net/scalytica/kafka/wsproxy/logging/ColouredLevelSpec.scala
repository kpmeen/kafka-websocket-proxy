package net.scalytica.kafka.wsproxy.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.LoggingEvent
import org.scalatest.BeforeAndAfterEach
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.io.AnsiColor

class ColouredLevelSpec
    extends AnyWordSpec
    with Matchers
    with BeforeAndAfterEach {

  override def afterEach(): Unit = {
    super.afterEach()
    val _ = sys.props -= "wsproxy.log.noformat"
  }

  val instance = new ColouredLevel

  private[this] def createLogEvent(level: Level): LoggingEvent = {
    val le = new LoggingEvent()
    le.setLoggerName(classOf[ColouredLevelSpec].getCanonicalName)
    le.setLevel(level)
    le.setMessage(s"Testing ${level.levelStr.toLowerCase} output")
    le.setTimeStamp(System.currentTimeMillis())
    le
  }

  "The ColouredLevel" should {

    "not add any colour if disabled via system property" in {
      sys.props += "wsproxy.log.noformat" -> "true"

      val le  = createLogEvent(Level.TRACE)
      val res = instance.convert(le)

      res mustBe Level.TRACE.levelStr
    }

    "add colour to error level " in {
      val le  = createLogEvent(Level.ERROR)
      val res = instance.convert(le)

      res mustBe AnsiColor.RED + Level.ERROR.levelStr + AnsiColor.RESET
    }

    "add colour to warn level " in {
      val le  = createLogEvent(Level.WARN)
      val res = instance.convert(le)

      res mustBe AnsiColor.YELLOW + Level.WARN.levelStr + AnsiColor.RESET
    }

    "add colour to info level " in {
      val le  = createLogEvent(Level.INFO)
      val res = instance.convert(le)

      res mustBe AnsiColor.WHITE + Level.INFO.levelStr + AnsiColor.RESET
    }

    "add colour to debug level " in {
      val le  = createLogEvent(Level.DEBUG)
      val res = instance.convert(le)

      res mustBe AnsiColor.CYAN + Level.DEBUG.levelStr + AnsiColor.RESET
    }

    "add colour to trace level " in {
      val le  = createLogEvent(Level.TRACE)
      val res = instance.convert(le)

      res mustBe AnsiColor.BLUE + Level.TRACE.levelStr + AnsiColor.RESET
    }
  }

}
