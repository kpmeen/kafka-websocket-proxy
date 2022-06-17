package net.scalytica.kafka.wsproxy.config

import akka.actor.typed.Scheduler
import akka.util.Timeout
import net.scalytica.kafka.wsproxy.actor.ActorWithProtocolExtensions
import net.scalytica.kafka.wsproxy.config.Configuration.{
  ConsumerSpecificLimitCfg,
  DynamicCfg,
  ProducerSpecificLimitCfg
}
import net.scalytica.kafka.wsproxy.errors.{
  FatalProxyServerError,
  RetryFailedError
}
import net.scalytica.kafka.wsproxy.config.DynamicConfigHandlerProtocol._
import net.scalytica.kafka.wsproxy.logging.WithProxyLogger
import net.scalytica.kafka.wsproxy.models.{WsGroupId, WsProducerId}
import net.scalytica.kafka.wsproxy.utils.BlockingRetry

import java.util.concurrent.TimeoutException
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

object DynamicConfigHandlerImplicits extends WithProxyLogger {

  /**
   * Contains extension methods for actor references to the
   * [[DynamicConfigHandler]].
   *
   * @param handler
   *   The [[DynamicConfigHandlerRef]] to extend.
   */
  implicit final class DynamicConfigHandlerProtocolExtensions(
      val handler: RunnableDynamicConfigHandlerRef
  ) extends BaseExtension[RunnableDynamicConfigHandlerRef]
      with RunnableExtensions
      with WriteExtensions

  implicit final class ReadableDynamicConfigHandlerProtocolExtensions(
      val handler: ReadableDynamicConfigHandlerRef
  ) extends BaseExtension[ReadableDynamicConfigHandlerRef]
      with ReadExtensions

  // scalastyle:off
  /**
   * Root for traits that implement different ops against the actor reference.
   *
   * @tparam T
   *   Sub-type of [[DynamicConfigHandlerRef]]
   */
  trait BaseExtension[T <: DynamicConfigHandlerRef]
      extends ActorWithProtocolExtensions[
        DynamicConfigProtocol,
        DynamicConfigOpResult
      ] {
    val handler: T
    override val ref = handler.dchRef
  }
  // scalastyle:on

  /**
   * Extensions that control the runtime and lifecycle of the actor reference.
   */
  trait RunnableExtensions {
    self: BaseExtension[_ <: DynamicConfigHandlerRef] =>
    /**
     * A blocking function that will try to wait for the
     * [[DynamicConfigHandler]] to complete its initialisation and restoration
     * of any [[DynamicCfg]] records on the Kafka topic.
     *
     * @param ec
     *   The [[ExecutionContext]] to use.
     * @param scheduler
     *   The [[Scheduler]] to use.
     * @return
     *   Will return a [[ConfigsRestored]] if successful.
     */
    @throws[FatalProxyServerError]
    def awaitRestoration()(
        implicit ec: ExecutionContext,
        scheduler: Scheduler
    ): ConfigsRestored = {
      val retries = 100
      BlockingRetry.retryAwaitFuture(60 seconds, 500 millis, retries) {
        attemptTimeout =>
          implicit val at: Timeout = attemptTimeout

          doAsk(CheckIfReady.apply).map {
            case cr: ConfigsRestored =>
              log.trace("Dynamic configs restored.")
              cr

            case _: RestoringConfigs =>
              log.trace("Dynamic configs are still being restored...")
              throw RetryFailedError("Dynamic configs are not ready.")

            case _ =>
              throw FatalProxyServerError(
                "Unexpected error when restoring state for dynamic config " +
                  "handler. Expected one of:" +
                  s"[${classOf[ConfigsRestored].niceClassSimpleName} |" +
                  s" ${classOf[RestoringConfigs].niceClassSimpleName}]"
              )
          }
      } { t =>
        throw FatalProxyServerError(
          message = "Unable to restore dynamic configurations",
          cause = Option(t)
        )
      }
    }

    /**
     * Method that will tell the [[DynamicConfigHandler]] to stop.
     *
     * @param ec
     *   The [[ExecutionContext]] to use.
     * @param timeout
     *   The [[Timeout]] limit to wait for the operation to complete.
     * @param scheduler
     *   The [[Scheduler]] to use.
     * @return
     *   Eventually returns a [[DynamicConfigOpResult]] of type [[Stopped]] when
     *   successful, or [[IncompleteOp]] in case of timeout.
     */
    @throws[IllegalStateException]
    def dynamicConfigHandlerStop()(
        implicit ec: ExecutionContext,
        timeout: Timeout,
        scheduler: Scheduler
    ): Future[DynamicConfigOpResult] = {
      doAskWithStandardRecovery(StopConfigHandler.apply) { case (msg, t) =>
        IncompleteOp(s"$msg ${t.getMessage}", Option(t))
      }
    }

  }

  /** Extensions that allow for state altering operations. */
  trait WriteExtensions extends ReadExtensions {
    self: BaseExtension[_ <: DynamicConfigHandlerRef] =>

    /**
     * Method that will tell the [[DynamicConfigHandler]] to add a new
     * [[DynamicCfg]].
     *
     * @param dynamicCfg
     *   The [[DynamicCfg]] to add.
     * @param ec
     *   The [[ExecutionContext]] to use.
     * @param timeout
     *   The [[Timeout]] limit to wait for the operation to complete.
     * @param scheduler
     *   The [[Scheduler]] to use.
     * @return
     *   Eventually returns a [[DynamicConfigOpResult]] of type [[ConfigSaved]]
     *   when successful, or [[IncompleteOp]] in case of timeout.
     */
    @throws[IllegalStateException]
    def addConfig(dynamicCfg: DynamicCfg)(
        implicit ec: ExecutionContext,
        timeout: Timeout,
        scheduler: Scheduler
    ): Future[DynamicConfigOpResult] = {
      dynamicCfgTopicKey(dynamicCfg) match {
        case Some(_) =>
          doAskWithStandardRecovery(ref => Save(dynamicCfg, ref)) {
            case (msg, t) => IncompleteOp(msg, Option(t))
          }

        case None =>
          log.warn("Attempted to save a dynamic config with an invalid key.")
          Future.successful(InvalidKey())
      }
    }

    /**
     * Method that will try to update a [[DynamicCfg]] instance in the
     * [[DynamicConfigHandler]]. If the [[DynamicCfg]] is not found a
     * [[ConfigNotFound]] is returned. If for, some reason, the operation could
     * not be completed an [[IncompleteOp]] is returned. When successful a
     * [[ConfigSaved]] is returned.
     *
     * @param dynamicCfg
     *   The [[DynamicCfg]] to update.
     * @param ec
     *   The [[ExecutionContext]] to use.
     * @param timeout
     *   The [[Timeout]] limit to wait for the operation to complete.
     * @param scheduler
     *   The [[Scheduler]] to use.
     * @return
     *   Eventually returns a [[DynamicConfigOpResult]] of type [[ConfigSaved]]
     *   when successful, or [[IncompleteOp]] in case of timeout.
     */
    @throws[IllegalStateException]
    def updateConfig(dynamicCfg: DynamicCfg)(
        implicit ec: ExecutionContext,
        timeout: Timeout,
        scheduler: Scheduler
    ): Future[DynamicConfigOpResult] = {
      dynamicCfgTopicKey(dynamicCfg) match {
        case Some(key) =>
          findConfig(key).flatMap {
            case Some(_) =>
              addConfig(dynamicCfg)

            case None =>
              Future.successful(ConfigNotFound(key))
          }

        case None =>
          log.warn("Attempted to save a dynamic config with an invalid key.")
          Future.successful(IncompleteOp("Invalid key"))
      }
    }

    /**
     * Method that will tell the [[DynamicConfigHandler]] to remove the
     * [[DynamicCfg]] associated with the given key.
     *
     * @param key
     *   The [[String]] key to remove config for.
     * @param ec
     *   The [[ExecutionContext]] to use.
     * @param timeout
     *   The [[Timeout]] limit to wait for the operation to complete.
     * @param scheduler
     *   The [[Scheduler]] to use.
     * @return
     *   Eventually returns a [[DynamicConfigOpResult]] of type
     *   [[ConfigRemoved]] when successful, or [[IncompleteOp]] in case of
     *   timeout.
     */
    @throws[IllegalStateException]
    def removeConfig(key: String)(
        implicit ec: ExecutionContext,
        timeout: Timeout,
        scheduler: Scheduler
    ): Future[DynamicConfigOpResult] = {
      doAskWithStandardRecovery(ref => Remove(key, ref)) { case (msg, t) =>
        IncompleteOp(s"$msg ${t.getMessage}", Option(t))
      }
    }

    /**
     * Method that will tell the [[DynamicConfigHandler]] to remove the
     * [[DynamicCfg]] associated with the [[WsGroupId]].
     *
     * @param grpId
     *   The [[WsGroupId]] to remove config for.
     * @param ec
     *   The [[ExecutionContext]] to use.
     * @param timeout
     *   The [[Timeout]] limit to wait for the operation to complete.
     * @param scheduler
     *   The [[Scheduler]] to use.
     * @return
     *   Eventually returns a [[DynamicConfigOpResult]] of type
     *   [[ConfigRemoved]] when successful, or [[IncompleteOp]] in case of
     *   timeout.
     */
    @throws[IllegalStateException]
    def removeConsumerConfig(grpId: WsGroupId)(
        implicit ec: ExecutionContext,
        timeout: Timeout,
        scheduler: Scheduler
    ): Future[DynamicConfigOpResult] = removeConfig(consumerCfgKey(grpId))

    /**
     * Method that will tell the [[DynamicConfigHandler]] to remove the
     * [[DynamicCfg]] associated with the [[WsProducerId]].
     *
     * @param prdId
     *   The [[WsProducerId]] to remove config for.
     * @param ec
     *   The [[ExecutionContext]] to use.
     * @param timeout
     *   The [[Timeout]] limit to wait for the operation to complete.
     * @param scheduler
     *   The [[Scheduler]] to use.
     * @return
     *   Eventually returns a [[DynamicConfigOpResult]] of type
     *   [[ConfigRemoved]] when successful, or [[IncompleteOp]] in case of
     *   timeout.
     */
    @throws[IllegalStateException]
    def removeProducerConfig(prdId: WsProducerId)(
        implicit ec: ExecutionContext,
        timeout: Timeout,
        scheduler: Scheduler
    ): Future[DynamicConfigOpResult] = removeConfig(producerCfgKey(prdId))

    /**
     * Method that will tell the [[DynamicConfigHandler]] to remove ALL the
     * [[DynamicCfg]] records from the Kafka topic.
     *
     * @param ec
     *   The [[ExecutionContext]] to use.
     * @param timeout
     *   The [[Timeout]] limit to wait for the operation to complete.
     * @param scheduler
     *   The [[Scheduler]] to use.
     * @return
     *   Eventually returns a [[DynamicConfigOpResult]] of type
     *   [[RemovedAllConfigs]] when successful, or [[IncompleteOp]] in case of
     *   timeout.
     */
    @throws[IllegalStateException]
    def removeAllConfigs()(
        implicit ec: ExecutionContext,
        timeout: Timeout,
        scheduler: Scheduler
    ): Future[DynamicConfigOpResult] = {
      doAskWithStandardRecovery(RemoveAll.apply) { case (msg, t) =>
        IncompleteOp(s"$msg ${t.getMessage}", Option(t))
      }
    }
  }

  /** Extensions that allow for read operations */
  trait ReadExtensions { self: BaseExtension[_ <: DynamicConfigHandlerRef] =>
    /**
     * Retrieve all the currently known active dynamic configurations available
     * in the [[DynamicConfigHandler]].
     *
     * @param ec
     *   The [[ExecutionContext]] to use.
     * @param timeout
     *   The [[Timeout]] duration defining how long to wait.
     * @param scheduler
     *   The [[Scheduler]] to use.
     * @return
     *   Eventually returns a [[DynamicConfigOpResult]] that will be of type
     *   [[FoundActiveConfigs]] when successful, or [[IncompleteOp]] in case of
     *   timeout.
     */
    @throws[IllegalStateException]
    def getAllConfigs()(
        implicit ec: ExecutionContext,
        timeout: Timeout,
        scheduler: Scheduler
    ): Future[DynamicConfigOpResult] = {
      doAskWithStandardRecovery(GetAll.apply) { case (msg, t) =>
        IncompleteOp(s"$msg ${t.getMessage}", Option(t))
      }
    }

    /**
     * Try to find a [[DynamicCfg]] for the given key.
     *
     * @param key
     *   The key to find a config for.
     * @param ec
     *   The [[ExecutionContext]] to use
     * @param timeout
     *   The [[Timeout]] duration defining how long to wait.
     * @param scheduler
     *   The [[Scheduler]] to use.
     * @return
     *   Eventually returns an [[Option]] with the result. If the operation
     *   encounters a [[TimeoutException]], the result will also be [[None]].
     */
    @throws[IllegalStateException]
    protected[this] def findConfig(key: String)(
        implicit ec: ExecutionContext,
        timeout: Timeout,
        scheduler: Scheduler
    ): Future[Option[DynamicCfg]] = {
      doAsk(ref => FindConfig(key, ref))
        .map {
          case ConfigNotFound(key) =>
            log.trace(
              s"There are no dynamic client configs for $key."
            )
            None

          case FoundConfig(key, cfg) =>
            log.trace(s"Found dynamic config for $key")
            Option(cfg)

          case IncompleteOp(reason, _) =>
            log.info(
              s"Operation to find dynamic config for $key was not " +
                s"completed correctly. Reason: $reason"
            )
            None

          case res =>
            log.warn(
              s"A very unexpected result of type [${res.niceClassSimpleName}]" +
                s" was returned when trying to find dynamic config for key $key"
            )
            None
        }
        .recoverWith {
          case t: TimeoutException =>
            val msg =
              "Timeout calling findConfig(). Will use default " +
                s"config for client $key."
            log.debug(msg, t)
            Future.successful(None)

          case t: Throwable =>
            log.warn("Unknown error calling findConfig().", t)
            throw t
        }
    }

    /**
     * Try to find a [[DynamicCfg]] for a consumer with the given [[WsGroupId]].
     *
     * @param groupId
     *   The [[WsGroupId]] to find a config for.
     * @param ec
     *   The [[ExecutionContext]] to use
     * @param timeout
     *   The [[Timeout]] duration defining how long to wait.
     * @param scheduler
     *   The [[Scheduler]] to use.
     * @return
     *   Eventually returns an [[Option]] with the result. If the operation
     *   encounters a [[TimeoutException]], the result will also be [[None]].
     */
    @throws[IllegalStateException]
    def findConfigForConsumer(groupId: WsGroupId)(
        implicit ec: ExecutionContext,
        timeout: Timeout,
        scheduler: Scheduler
    ): Future[Option[ConsumerSpecificLimitCfg]] = {
      findConfig(consumerCfgKey(groupId)).map { mc =>
        mc.flatMap {
          case psl: ConsumerSpecificLimitCfg => Some(psl)
          case _ =>
            log.warn(
              s"Unexpected cfg type for consumer group ${groupId.value}"
            )
            None
        }
      }
    }

    /**
     * Try to find a [[DynamicCfg]] for a producer with the given
     * [[WsProducerId]].
     *
     * @param producerId
     *   The [[WsProducerId]] to find a config for.
     * @param ec
     *   The [[ExecutionContext]] to use
     * @param timeout
     *   The [[Timeout]] duration defining how long to wait.
     * @param scheduler
     *   The [[Scheduler]] to use.
     * @return
     *   Eventually returns an [[Option]] with the result. If the operation
     *   encounters a [[TimeoutException]], the result will also be [[None]].
     */
    @throws[IllegalStateException]
    def findConfigForProducer(producerId: WsProducerId)(
        implicit ec: ExecutionContext,
        timeout: Timeout,
        scheduler: Scheduler
    ): Future[Option[ProducerSpecificLimitCfg]] = {
      findConfig(producerCfgKey(producerId)).map { mc =>
        mc.flatMap {
          case psl: ProducerSpecificLimitCfg => Some(psl)
          case _ =>
            log.warn(
              s"Unexpected cfg type for producer ${producerId.value}"
            )
            None
        }
      }
    }
  }

}
