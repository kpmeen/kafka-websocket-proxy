package net.scalytica.kafka

import org.apache.pekko.util.ByteString
import com.typesafe.scalalogging.Logger
import io.confluent.monitoring.clients.interceptor.{
  MonitoringConsumerInterceptor,
  MonitoringProducerInterceptor
}
import net.scalytica.kafka.wsproxy.config.Configuration.AppCfg

import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.{Properties => JProps}
import scala.util.Try
// scalastyle:off
import org.apache.kafka.clients.consumer.ConsumerConfig.INTERCEPTOR_CLASSES_CONFIG
// scalastyle:on

import scala.jdk.FunctionConverters
import scala.jdk.FutureConverters._
import scala.concurrent.Future

package object wsproxy {

  val SaslJaasConfig: String = "sasl.jaas.config"

  val ProducerInterceptorClass: String =
    classOf[MonitoringProducerInterceptor[_, _]].getName

  val ConsumerInterceptorClass: String =
    classOf[MonitoringConsumerInterceptor[_, _]].getName

  def monitoringProperties(
      interceptorClassStr: String
  )(implicit cfg: AppCfg): Map[String, String] = {
    if (cfg.kafkaClient.monitoringEnabled) {
      // Enables stream monitoring in confluent control center
      Map(INTERCEPTOR_CLASSES_CONFIG -> interceptorClassStr) ++
        cfg.kafkaClient.confluentMonitoring
          .map(cmr => cmr.asPrefixedProperties)
          .getOrElse(Map.empty[String, String])
    } else {
      Map.empty[String, String]
    }
  }

  def producerMetricsProperties(implicit cfg: AppCfg): Map[String, String] =
    monitoringProperties(ProducerInterceptorClass)

  def consumerMetricsProperties(implicit cfg: AppCfg): Map[String, String] =
    monitoringProperties(ConsumerInterceptorClass)

  implicit def mapToProperties(m: Map[String, String]): JProps = {
    val props = new JProps()
    m.foreach(kv => props.put(kv._1, kv._2))
    props
  }

  implicit class NiceClassNameExtensions(c: Any) {

    def niceClassName: String = c.getClass.getName.stripSuffix("$")

    def niceClassNameShort: String = niceClassName.split("""\.""").last

    def niceClassSimpleName: String = c.getClass.getSimpleName.stripSuffix("$")

    def packageName: String = c.getClass.getPackageName
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

    def asOption: Option[String] = Option(underlying).filterNot(_.isBlank)

    def safeNonEmpty: Boolean =
      Option(underlying).filterNot(_.isBlank).map(_.nonEmpty).nonEmpty
  }

  implicit class JavaFutureConverter[A](
      val jf: java.util.concurrent.Future[A]
  ) {

    def toScalaFuture: Future[A] = {
      val sup = FunctionConverters.enrichAsJavaSupplier[A](() => jf.get)
      try {
        CompletableFuture.supplyAsync(sup.asJavaSupplier).asScala
      } catch {
        case juce: java.util.concurrent.ExecutionException =>
          Option(juce.getCause).map(cause => throw cause).getOrElse(throw juce)
      }
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

  implicit class SafeByteStringExtensions(val bs: ByteString) {

    /**
     * Safely decodes a [[ByteString]] into an optional String.
     *
     * @return
     *   An Option of String.
     */
    def decodeSafeString: Option[String] =
      Try(bs.decodeString(StandardCharsets.UTF_8)).toOption.flatMap {
        case ""    => None
        case value => Some(value)
      }

  }

  implicit class ThrowableExtensions(t: Throwable) {

    def operationName: String = {
      t.getStackTrace
        .dropWhile(ste => !ste.getClassName.equals(getClass.getName))
        .headOption
        .map(_.getMethodName)
        .getOrElse("unknown")
    }

  }
}
