package net.scalytica.kafka

import java.util.concurrent.CompletableFuture

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
      underlying.getOrElse {
        throw new NoSuchElementException(
          "Cannot lift the value from the Option[T] because it was empty."
        )
      }
    }
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
}
