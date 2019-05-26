package net.scalytica.kafka.wsproxy.errors

import scala.util.control.NoStackTrace

case class TopicNotFoundError(message: String)
    extends Exception(message)
    with NoStackTrace
