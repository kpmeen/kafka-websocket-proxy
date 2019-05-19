package net.scalytica.kafka.wsproxy.utils

import java.net.InetAddress

import com.typesafe.scalalogging.Logger
import net.scalytica.kafka.wsproxy.Configuration.{AppCfg, KafkaBootstrapUrls}

import scala.annotation.tailrec
import scala.concurrent.blocking
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

object HostResolver {

  private[this] val logger = Logger(getClass)

  case class HostResolutionError(reason: String)

  def resolveKafkaBootstrapHosts(
      bootstrapUrls: KafkaBootstrapUrls
  )(implicit cfg: AppCfg): Either[HostResolutionError, InetAddress] = {
    val retryInterval = 1 second
    val retryFor      = cfg.kafkaClient.brokerResolutionTimeout
    val deadline      = retryFor.fromNow

    @tailrec
    def resolve(host: String): Either[HostResolutionError, InetAddress] = {
      Try(InetAddress.getByName(host)) match {
        case Success(addr) =>
          logger.info(s"Successfully resolved host $host")
          Right(addr)

        case Failure(_) =>
          if (deadline.hasTimeLeft()) {
            logger.info(s"Resolution of host $host failed, retrying...")
            sleep(retryInterval)
            resolve(host)
          } else {
            logger.warn(s"Resolution of host $host failed, no more retries.")
            Left(
              HostResolutionError(
                s"$host could not be resolved within time limit of $retryFor."
              )
            )
          }
      }
    }

    bootstrapUrls.hosts.headOption
      .map(resolve)
      .getOrElse(Left(HostResolutionError("NOT DEFINED")))
  }

  private[this] def sleep(duration: FiniteDuration): Unit =
    try {
      blocking(Thread.sleep(duration.toMillis))
    } catch {
      case e: InterruptedException =>
        Thread.currentThread().interrupt()
        throw e
    }

}
