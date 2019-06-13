package net.scalytica.kafka

import java.util.concurrent.CompletableFuture
import java.util.{Properties => JProps}

import com.typesafe.scalalogging.Logger
import io.confluent.monitoring.clients.interceptor.{
  MonitoringConsumerInterceptor,
  MonitoringProducerInterceptor
}

import scala.compat.java8.{FunctionConverters, FutureConverters}
import scala.concurrent.Future

package object wsproxy {

  val ProducerInterceptorClass =
    classOf[MonitoringProducerInterceptor[_, _]].getName

  val ConsumerInterceptorClass =
    classOf[MonitoringConsumerInterceptor[_, _]].getName

  implicit def mapToProperties(m: Map[String, AnyRef]): JProps = {
    val props = new JProps()
    m.foreach(kv => props.put(kv._1, kv._2))
    props
  }

  implicit class OptionExtensions[T](underlying: Option[T]) {

    def getUnsafe: T = {
      orThrow(
        new NoSuchElementException(
          "Cannot lift the value from the Option[T] because it was empty."
        )
      )
    }

    def orThrow(t: Throwable): T = underlying.getOrElse(throw t)
  }

  implicit class StringExtensions(val underlying: String) extends AnyVal {

    def toSnakeCase: String = {
      underlying.foldLeft("") { (str, c) =>
        if (c.isUpper) {
          if (str.isEmpty) str + c.toLower
          else str + "_" + c.toLower
        } else {
          str + c
        }
      }
    }
  }

  implicit class JavaFutureConverter[A](
      val jf: java.util.concurrent.Future[A]
  ) extends AnyVal {

    def toScalaFuture: Future[A] = {
      val sup = FunctionConverters.asJavaSupplier[A](() => jf.get)
      FutureConverters.toScala(CompletableFuture.supplyAsync(sup))
    }
  }

  implicit class LoggerExtensions(val logger: Logger) {
    private[this] def wrapInFuture(stmnt: => Unit): Future[Unit] =
      Future.successful(stmnt)

    def errorf(message: String): Future[Unit] =
      wrapInFuture(logger.error(message))

    def errorf(message: String, cause: Throwable): Future[Unit] =
      wrapInFuture(logger.error(message, cause))

    def warnf(message: String): Future[Unit] =
      wrapInFuture(logger.warn(message))

    def warnf(message: String, cause: Throwable): Future[Unit] =
      wrapInFuture(logger.warn(message, cause))

    def infof(message: String): Future[Unit] =
      wrapInFuture(logger.info(message))

    def infof(message: String, cause: Throwable): Future[Unit] =
      wrapInFuture(logger.info(message, cause))

    def debugf(message: String): Future[Unit] =
      wrapInFuture(logger.debug(message))

    def debugf(message: String, cause: Throwable): Future[Unit] =
      wrapInFuture(logger.debug(message, cause))
  }
}
