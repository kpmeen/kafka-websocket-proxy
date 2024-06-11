package net.scalytica.kafka.wsproxy.errors

import java.util.concurrent.{ExecutionException => JExecException}

import org.apache.kafka.common.errors.TopicExistsException

object KafkaFutureErrorHandler {

  def handle[A](
      ok: => A
  )(
      ko: Throwable => A
  ): PartialFunction[Throwable, A] = {
    case juce: JExecException =>
      Option(juce.getCause)
        .map {
          case _: TopicExistsException => ok
          case _                       => ko(juce)
        }
        .getOrElse(ko(juce))

    case t =>
      ko(t)
  }

}
