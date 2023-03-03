package net.scalytica.kafka.wsproxy.utils

import java.time.{Duration => JDuration}

import scala.concurrent.duration.{Duration, FiniteDuration}

object JavaDurationConverters {

  implicit final class JavaDurationOps(val self: JDuration) extends AnyVal {
    def asScala: FiniteDuration = Duration.fromNanos(self.toNanos)
  }

  implicit final class ScalaDurationOps(val self: Duration) extends AnyVal {
    def asJava: JDuration = JDuration.ofNanos(self.toNanos)
  }
}
