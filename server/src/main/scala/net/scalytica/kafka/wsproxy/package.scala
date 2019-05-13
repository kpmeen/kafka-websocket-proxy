package net.scalytica.kafka

import java.util.concurrent.CompletableFuture

import com.typesafe.scalalogging.Logger
import io.confluent.monitoring.clients.interceptor.{
  MonitoringConsumerInterceptor,
  MonitoringProducerInterceptor
}
import org.apache.kafka.common.KafkaFuture

import scala.concurrent.{Future, Promise}
import scala.util.Try
import scala.util.control.NonFatal
import scala.compat.java8.FunctionConverters
import scala.compat.java8.FutureConverters

package object wsproxy {

  val ProducerInterceptorClass =
    classOf[MonitoringProducerInterceptor[_, _]].getName

  val ConsumerInterceptorClass =
    classOf[MonitoringConsumerInterceptor[_, _]].getName

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

  implicit class KafkaFutureConverter[A](val k: KafkaFuture[A]) extends AnyVal {

    def call[B](f: A => B): Future[B] = {
      val promise = Promise[B]()

      try {
        k.thenApply { n: A =>
          Try(f(n))
        }
      } catch {
        case NonFatal(e) => promise.failure(e)
      }

      promise.future
    }
  }

  implicit class KafkaFutureVoidConverter(val k: KafkaFuture[Void])
      extends AnyVal {

    def callVoid(): Future[Unit] = k.call(_ => ())
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
