package net.scalytica.kafka.wsproxy.logging

import com.typesafe.scalalogging.Logger

/**
 * Convenience trait for providing loggers to different objects and classes.
 */
trait WithProxyLogger { self =>

  protected lazy val logger = Logger(self.getClass.getName.stripSuffix("$"))

}
