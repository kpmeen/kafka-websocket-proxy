package net.scalytica.kafka.wsproxy.utils

import java.net.InetAddress

import com.typesafe.scalalogging.Logger
import net.scalytica.kafka.wsproxy.Configuration.{AppCfg, KafkaBootstrapHosts}

import scala.annotation.tailrec
import scala.concurrent.blocking
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

object HostResolver {

  private[this] val logger = Logger(getClass)

  sealed trait HostResolutionResult {
    def isSuccess: Boolean
    def isError: Boolean = !isSuccess
  }

  case class HostResolved(host: InetAddress) extends HostResolutionResult {
    override def isSuccess = true
  }

  case class HostResolutionError(reason: String) extends HostResolutionResult {
    override def isSuccess = false
  }

  case class HostResolutionStatus(results: List[HostResolutionResult]) {

    def hasErrors: Boolean = results.exists(_.isError)

  }

  /**
   * Will try to resolve all the kafka hosts configured in the
   * {{{kafka.ws.proxy.kafka-client.bootstrap-hosts}}} configuration property.
   *
   * This is a workaround for https://github.com/apache/kafka/pull/6305.
   *
   * @param bootstrapHosts the kafka hosts to resolve
   * @param cfg the application configuration to use
   * @return an instance of [[HostResolutionStatus]] containing all
   *         [[HostResolutionResult]]s for the configured Kafka bootstrap hosts.
   */
  def resolveKafkaBootstrapHosts(
      bootstrapHosts: KafkaBootstrapHosts
  )(implicit cfg: AppCfg): HostResolutionStatus = {
    val retryInterval = 1 second
    val retryFor      = cfg.kafkaClient.brokerResolutionTimeout
    val deadline      = retryFor.fromNow

    @tailrec
    def resolve(host: String): HostResolutionResult = {
      Try(InetAddress.getByName(host)) match {
        case Success(addr) =>
          logger.info(s"Successfully resolved host $host")
          HostResolved(addr)

        case Failure(_) =>
          if (deadline.hasTimeLeft()) {
            logger.info(s"Resolution of host $host failed, retrying...")
            sleep(retryInterval)
            resolve(host)
          } else {
            logger.warn(s"Resolution of host $host failed, no more retries.")
            HostResolutionError(
              s"$host could not be resolved within time limit of $retryFor."
            )
          }
      }
    }

    if (bootstrapHosts.hosts.nonEmpty) {
      val resolutions = bootstrapHosts.hostsString.map(resolve)
      HostResolutionStatus(resolutions)
    } else {
      HostResolutionStatus(List(HostResolutionError("NOT DEFINED")))
    }
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
