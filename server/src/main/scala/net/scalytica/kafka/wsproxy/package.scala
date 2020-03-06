package net.scalytica.kafka

import java.util.concurrent.CompletableFuture
import java.util.{Properties => JProps}

import akka.NotUsed
import akka.http.scaladsl.model.ws.{BinaryMessage, Message, TextMessage}
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Sink}
import akka.util.ByteString
import com.typesafe.scalalogging.Logger
import io.confluent.monitoring.clients.interceptor.{
  MonitoringConsumerInterceptor,
  MonitoringProducerInterceptor
}
import net.scalytica.kafka.wsproxy.Configuration.AppCfg

import scala.concurrent.ExecutionContext
// scalastyle:off
import org.apache.kafka.clients.consumer.ConsumerConfig.INTERCEPTOR_CLASSES_CONFIG
// scalastyle:on

import scala.compat.java8.{FunctionConverters, FutureConverters}
import scala.concurrent.duration._
import scala.concurrent.Future

package object wsproxy {

  def wsMessageToStringFlow(
      implicit
      mat: Materializer,
      ec: ExecutionContext
  ): Flow[Message, String, NotUsed] =
    Flow[Message]
      .mapConcat {
        case tm: TextMessage   => TextMessage(tm.textStream) :: Nil
        case bm: BinaryMessage => bm.dataStream.runWith(Sink.ignore); Nil
      }
      .mapAsync(1)(_.toStrict(5 seconds).map(_.text))

  def wsMessageToByteStringFlow(
      implicit
      mat: Materializer,
      ec: ExecutionContext
  ): Flow[Message, ByteString, NotUsed] =
    Flow[Message]
      .mapConcat {
        case tm: TextMessage   => tm.textStream.runWith(Sink.ignore); Nil
        case bm: BinaryMessage => BinaryMessage(bm.dataStream) :: Nil
      }
      .mapAsync(1)(_.toStrict(5 seconds).map(_.data))

  val ProducerInterceptorClass =
    classOf[MonitoringProducerInterceptor[_, _]].getName

  val ConsumerInterceptorClass =
    classOf[MonitoringConsumerInterceptor[_, _]].getName

  def monitoringProperties(
      interceptorClassStr: String
  )(implicit cfg: AppCfg): Map[String, AnyRef] = {
    if (cfg.kafkaClient.monitoringEnabled) {
      // Enables stream monitoring in confluent control center
      Map(INTERCEPTOR_CLASSES_CONFIG -> interceptorClassStr) ++
        cfg.kafkaClient.confluentMonitoring
          .map(cmr => cmr.asPrefixedProperties)
          .getOrElse(Map.empty[String, AnyRef])
    } else {
      Map.empty[String, AnyRef]
    }
  }

  def producerMetricsProperties(implicit cfg: AppCfg): Map[String, AnyRef] =
    monitoringProperties(ProducerInterceptorClass)

  def consumerMetricsProperties(implicit cfg: AppCfg): Map[String, AnyRef] =
    monitoringProperties(ConsumerInterceptorClass)

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
