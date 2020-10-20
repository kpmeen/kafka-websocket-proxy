package net.scalytica.kafka.wsproxy.utils

import java.net.InetAddress

import net.scalytica.kafka.wsproxy.config.Configuration.{
  AppCfg,
  KafkaBootstrapHosts
}
import net.scalytica.kafka.wsproxy.logging.WithProxyLogger
import net.scalytica.kafka.wsproxy.utils.BlockingRetry.retry

import scala.util.Try

object HostResolver extends WithProxyLogger {

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

  def resolveHost(
      host: String,
      errMsg: Option[String] = None
  ): HostResolutionResult =
    Try(InetAddress.getByName(host))
      .map { addr =>
        logger.info(s"Successfully resolved host $host")
        HostResolved(addr)
      }
      .getOrElse {
        val msg = errMsg.getOrElse(s"$host could not be resolved")
        HostResolutionError(msg)
      }

  /**
   * Will try to resolve all the kafka hosts configured in the
   * {{{kafka.ws.proxy.kafka-client.bootstrap-hosts}}} configuration property.
   *
   * This is a workaround for https://github.com/apache/kafka/pull/6305.
   *
   * @param bootstrapHosts
   *   the kafka hosts to resolve
   * @param cfg
   *   the application configuration to use
   * @return
   *   an instance of [[HostResolutionStatus]] containing all
   *   [[HostResolutionResult]] s for the configured Kafka bootstrap hosts.
   */
  def resolveKafkaBootstrapHosts(
      bootstrapHosts: KafkaBootstrapHosts
  )(implicit cfg: AppCfg): HostResolutionStatus = {
    val timeout = cfg.kafkaClient.brokerResolutionTimeout

    val resErrStr = (host: String) =>
      s"$host could not be resolved within time limit of $timeout."

    if (bootstrapHosts.hosts.nonEmpty) {
      val resolutions = bootstrapHosts.hostStrings.map { host =>
        retry[HostResolutionResult](
          timeout = timeout,
          interval = cfg.server.brokerResolutionRetryInterval,
          numRetries = cfg.server.brokerResolutionRetries
        ) {
          resolveHost(host, Some(resErrStr(host)))
        } { _ =>
          logger.warn(s"Resolution of host $host failed, no more retries.")
          HostResolutionError(
            s"$host could not be resolved within time limit of $timeout."
          )
        }
      }
      HostResolutionStatus(resolutions)
    } else {
      HostResolutionStatus(List(HostResolutionError("NOT DEFINED")))
    }
  }
}
