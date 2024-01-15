package net.scalytica.kafka.wsproxy.web.websockets

import org.apache.pekko.actor.typed.Scheduler
import org.apache.pekko.util.Timeout
import net.scalytica.kafka.wsproxy.config.Configuration.AppCfg
import net.scalytica.kafka.wsproxy.config.DynamicConfigHandlerImplicits._
import net.scalytica.kafka.wsproxy.config.ReadableDynamicConfigHandlerRef
import net.scalytica.kafka.wsproxy.logging.WithProxyLogger
import net.scalytica.kafka.wsproxy.models.{
  InSocketArgs,
  OutSocketArgs,
  SocketArgs
}
import net.scalytica.kafka.wsproxy.utils.BlockingRetry

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

trait ClientSpecificCfgLoader extends WithProxyLogger {

  private[this] val dynCfgTimeout  = 4 seconds
  private[this] val dynCfgInterval = 1 second
  private[this] val attempts       = 5

  protected def applyDynamicConfigs(args: SocketArgs)(appCfg: AppCfg)(
      implicit
      maybeDynamicCfgHandlerRef: Option[ReadableDynamicConfigHandlerRef],
      ctx: ExecutionContext,
      scheduler: Scheduler
  ): AppCfg = {
    BlockingRetry.retryAwaitFuture(dynCfgTimeout, dynCfgInterval, attempts) {
      timeout =>
        implicit val attemptTimeout: Timeout = timeout
        maybeDynamicCfgHandlerRef
          .map { ref =>
            args match {
              case isa: InSocketArgs =>
                ref.findConfigForProducer(isa.producerId).map {
                  case None      => appCfg
                  case Some(cfg) => appCfg.addProducerCfg(cfg)
                }
              case osa: OutSocketArgs =>
                ref.findConfigForConsumer(osa.groupId).map {
                  case None      => appCfg
                  case Some(cfg) => appCfg.addConsumerCfg(cfg)
                }
            }
          }
          .getOrElse(Future.successful(appCfg))
    } { ex =>
      log.warn(s"Unable to load dynamic config for request args: $args", ex)
      Future.successful(appCfg)
    }
  }

}
