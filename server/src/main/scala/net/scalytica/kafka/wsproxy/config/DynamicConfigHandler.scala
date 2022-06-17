package net.scalytica.kafka.wsproxy.config

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.stream.scaladsl.Sink
import net.scalytica.kafka.wsproxy.admin.WsKafkaAdminClient
import net.scalytica.kafka.wsproxy.config.Configuration.AppCfg
import net.scalytica.kafka.wsproxy.config.DynamicConfigHandlerProtocol._
import net.scalytica.kafka.wsproxy.logging.WithProxyLogger

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

object DynamicConfigHandler extends DynamicConfigHandler

/**
 * Logic for building a clustered handler, using a Kafka topic, for dynamic
 * configurations that applies to all instances of the server.
 *
 * The idea is to use a compacted Kafka topic as the source of truth for each
 * server instance. Each server instance will have a single actor that can read
 * and write from/to the topic containing dynamic configurations. The topic data
 * should always contain the latest up-to-date version of the configurations.
 */
trait DynamicConfigHandler extends WithProxyLogger {

  private[this] var latestOffset: Long     = 0L
  private[this] var stateRestored: Boolean = false

  /**
   * Calculates the expected next behaviour based on the value of the {{either}}
   * argument.
   *
   * @param either
   *   the value to calculate the next behaviour from.
   * @param behavior
   *   the behaviour to use in the case of a rhs value.
   * @return
   *   the next behaviour to use.
   */
  private[this] def nextOrSameBehavior(
      either: Option[DynamicConfigurations]
  )(
      behavior: DynamicConfigurations => Behavior[DynamicConfigProtocol]
  ): Behavior[DynamicConfigProtocol] = {
    either match {
      case None => Behaviors.same
      case Some(ns) =>
        log.trace(
          "State has changed. Current configs are now:\n" +
            s"${ns.configs.mkString("\n")}"
        )
        behavior(ns)
    }
  }

  /**
   * Method for preparing and verifying the dynamic config handler topic with
   * the name given in {{{kafka.ws.proxy.dynamic-config-handler.topic-name}}}.
   *
   * @param cfg
   *   The [[AppCfg]] to use.
   * @return
   *   The last known offset for the topic.
   */
  private[this] def prepareTopic(implicit cfg: AppCfg): Long = {
    val admin = new WsKafkaAdminClient(cfg)

    try {
      val ready = admin.clusterReady
      if (ready) admin.initDynamicConfigTopic()
      else {
        log.error("Could not reach Kafka cluster. Terminating application!")
        System.exit(1)
      }
      val offset = admin.lastOffsetForDynamicConfigTopic
      log.debug(s"Dynamic config topic ready. LAtest offset is $offset")
      if (latestOffset == 0) stateRestored = true
      offset
    } finally {
      admin.close()
    }
  }

  /**
   * Verifies whether state restoration has completed or is still in progress
   * when. Used when initialising the handler.
   *
   * @param cmd
   *   The current [[InternalCommand]] being processed.
   */
  private[this] def verifyRestoreStatus(cmd: InternalCommand): Unit = {
    if (!stateRestored && latestOffset <= cmd.offset) {
      stateRestored = true
      log.info(
        "Dynamic configs have been restored to " +
          s"offset ${cmd.offset}/$latestOffset"
      )
    }
  }

  /**
   * Initialises the dynamic config handler.
   *
   * @param cfg
   *   The [[AppCfg]] to use.
   * @param sys
   *   The [[akka.actor.ActorSystem]] to use.
   * @return
   *   An instance of [[RunnableDynamicConfigHandlerRef]].
   */
  def init(
      implicit cfg: AppCfg,
      sys: akka.actor.ActorSystem
  ): RunnableDynamicConfigHandlerRef = {
    implicit val typedSys = sys.toTyped

    val name = s"dynamic-config-handler-actor-${cfg.server.serverId.value}"
    log.debug(s"Initialising dynamic config handler $name")

    latestOffset = prepareTopic

    val ref = sys.spawn(dynamicConfigHandler, name)

    log.debug(s"Starting dynamic config consumer with identifier $name")
    val consumer = new DynamicConfigConsumer()

    val consumerStream =
      consumer.dynamicCfgSource.to {
        Sink.foreach {
          case upd: UpdateDynamicConfigRecord =>
            verifyRestoreStatus(upd)
            ref ! upd

          case rem: RemoveDynamicConfigRecord =>
            verifyRestoreStatus(rem)
            ref ! rem
        }
      }

    RunnableDynamicConfigHandlerRef(consumerStream, ref)
  }

  /**
   * The actual initialisation of the dynamic config handler.
   *
   * @param appCfg
   *   The [[AppCfg]] to use.
   * @return
   *   Behavior supporting the [[DynamicConfigProtocol]].
   */
  protected def dynamicConfigHandler(
      implicit appCfg: AppCfg
  ): Behavior[DynamicConfigProtocol] = {
    Behaviors.setup { implicit ctx =>
      implicit val sys = ctx.system
      implicit val ec  = sys.executionContext
      log.debug(s"Initialising dynamic config producer...")
      implicit val producer = new DynamicConfigProducer()
      behavior()
    }
  }

  /**
   * Behavior definition for the [[DynamicConfigProtocol]]
   *
   * @param active
   *   The currently active [[DynamicConfigurations]].
   * @param ec
   *   The [[ExecutionContext]] to use.
   * @param cfg
   *   The [[AppCfg]] to use.
   * @param producer
   *   An instance of a [[DynamicConfigProducer]].
   * @return
   *   Behavior supporting the [[DynamicConfigProtocol]].
   */
  private[this] def behavior(
      active: DynamicConfigurations = DynamicConfigurations()
  )(
      implicit ec: ExecutionContext,
      cfg: AppCfg,
      producer: DynamicConfigProducer
  ): Behavior[DynamicConfigProtocol] = {
    Behaviors.receiveMessage {
      case ops: OpCommand       => opCommandBehavior(ops)
      case cmd: Command         => commandBehavior(active, cmd)
      case int: InternalCommand => internalCommandBehavior(active, int)
    }
  }

  /**
   * Behavior definition for the administrative [[OpCommand]] subset of the
   * [[DynamicConfigProtocol]].
   *
   * @param cmd
   *   The [[OpCommand]] to process.
   * @param cfg
   *   The [[AppCfg]] to use.
   * @return
   *   Behavior supporting the [[DynamicConfigProtocol]].
   */
  private[this] def opCommandBehavior(cmd: OpCommand)(
      implicit cfg: AppCfg
  ): Behavior[DynamicConfigProtocol] = {
    cmd match {
      case CheckIfReady(replyTo) =>
        val currentState =
          if (stateRestored) ConfigsRestored()
          else RestoringConfigs()

        replyTo ! currentState
        Behaviors.same

      case StopConfigHandler(replyTo) =>
        log.debug(
          "STOP: stopping dynamic config handler for server" +
            s" ${cfg.server.serverId.value}"
        )

        Behaviors.stopped(() => replyTo ! Stopped())
    }
  }

  // scalastyle:off cyclomatic.complexity method.length
  /**
   * Behavior definition for the [[Command]] subset of the
   * [[DynamicConfigProtocol]].
   *
   * @param active
   *   The currently active [[DynamicConfigurations]].
   * @param cmd
   *   The [[Command]] to process.
   * @param ec
   *   The [[ExecutionContext]] to use.
   * @param producer
   *   An instance of a [[DynamicConfigProducer]].
   * @return
   *   Behavior supporting the [[DynamicConfigProtocol]].
   */
  private[this] def commandBehavior(
      active: DynamicConfigurations,
      cmd: Command
  )(
      implicit ec: ExecutionContext,
      producer: DynamicConfigProducer
  ): Behavior[DynamicConfigProtocol] = cmd match {
    case GetAll(replyTo) =>
      log.trace("Returning all active dynamic configs...")
      replyTo ! FoundActiveConfigs(active)
      Behaviors.same

    case FindConfig(key, replyTo) =>
      log.trace(s"Attempting to find config for $key...")
      val res = active
        .findByKey(key)
        .map(c => FoundConfig(key, c))
        .getOrElse(ConfigNotFound(key))
      log.trace(s"Looking up key $key returned $res")
      replyTo ! res
      Behaviors.same

    case Save(dcfg, replyTo) =>
      producer.publishConfig(dcfg).onComplete {
        case Success(_) =>
          replyTo ! ConfigSaved(dcfg)
          log.debug("Dynamic config successfully saved.")

        case Failure(err) =>
          log.warn(s"Unable to save dynamic cfg ${dcfg.asHoconString()}", err)
          replyTo ! IncompleteOp(err.getMessage)
      }
      Behaviors.same

    case Remove(key, replyTo) =>
      producer.removeConfig(key).onComplete {
        case Success(_) =>
          log.trace(s"Published event to remove dynamic config for $key")
          replyTo ! ConfigRemoved(key)

        case Failure(err) =>
          log.warn(s"Unable to remove dynamic config for $key", err)
          replyTo ! IncompleteOp(err.getMessage)
      }
      Behaviors.same

    case RemoveAll(replyTo) =>
      Future
        // TODO: Could be optimised to send all in one go to Kafka
        .sequence(active.keys.map(producer.removeConfig))
        .onComplete {
          case Success(_) =>
            log.trace("Published events to remove all dynamic configs.")
            replyTo ! RemovedAllConfigs()

          case Failure(err) =>
            log.warn("Unable to remove dynamic configs", err)
            replyTo ! IncompleteOp(err.getMessage)
        }
      Behaviors.same
  }
  // scalastyle:on cyclomatic.complexity method.length

  /**
   * Behavior definition for the [[InternalCommand]] subset of the
   * [[DynamicConfigProtocol]].
   *
   * @param active
   *   The currently active [[DynamicConfigurations]].
   * @param cmd
   *   The [[InternalCommand]] to process.
   * @param ec
   *   The [[ExecutionContext]] to use.
   * @param cfg
   *   The [[AppCfg]] to use.
   * @param producer
   *   An instance of a [[DynamicConfigProducer]].
   * @return
   *   Behavior supporting the [[DynamicConfigProtocol]].
   */
  private[this] def internalCommandBehavior(
      active: DynamicConfigurations,
      cmd: InternalCommand
  )(
      implicit ec: ExecutionContext,
      cfg: AppCfg,
      producer: DynamicConfigProducer
  ): Behavior[DynamicConfigProtocol] = {
    cmd match {
      case upd: UpdateDynamicConfigRecord =>
        log.debug("Received update dynamic config message...")
        nextOrSameBehavior(active.update(upd))(behavior)

      case rem: RemoveDynamicConfigRecord =>
        log.debug("Received remove dynamic config message...")
        nextOrSameBehavior(active.remove(rem))(behavior)
    }
  }
}
