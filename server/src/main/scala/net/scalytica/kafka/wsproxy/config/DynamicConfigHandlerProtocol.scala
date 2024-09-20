package net.scalytica.kafka.wsproxy.config

import net.scalytica.kafka.wsproxy.config.Configuration.DynamicCfg

import org.apache.pekko.actor.typed.ActorRef

object DynamicConfigHandlerProtocol {

  /*
      Response types from the DynamicConfigHandler
   */

  sealed trait DynamicConfigOpResult { self => }

  final case class ConfigsRestored()               extends DynamicConfigOpResult
  final case class RestoringConfigs()              extends DynamicConfigOpResult
  final case class ConfigSaved(cfg: DynamicCfg)    extends DynamicConfigOpResult
  final case class ConfigRemoved(topicKey: String) extends DynamicConfigOpResult
  final case class RemovedAllConfigs()             extends DynamicConfigOpResult
  final case class Stopped()                       extends DynamicConfigOpResult
  final case class ConfigNotFound(key: String)     extends DynamicConfigOpResult
  final case class FoundActiveConfigs(dc: DynamicConfigurations)
      extends DynamicConfigOpResult
  final case class FoundConfig(key: String, cfg: DynamicCfg)
      extends DynamicConfigOpResult
  final case class IncompleteOp(reason: String, t: Option[Throwable] = None)
      extends DynamicConfigOpResult
  final case class InvalidKey(maybeKey: Option[String] = None)
      extends DynamicConfigOpResult

  /** Main protocol to be used by the dynamic config handler */
  sealed trait DynamicConfigProtocol

  /*
      Operational commands
   */

  sealed trait OpCommand extends DynamicConfigProtocol

  final case class CheckIfReady(replyTo: ActorRef[DynamicConfigOpResult])
      extends OpCommand

  final case class StopConfigHandler(replyTo: ActorRef[DynamicConfigOpResult])
      extends OpCommand

  /*
      Allowed commands for the DynamicConfigHandler
   */

  sealed trait Command extends DynamicConfigProtocol

  final case class Save(
      cfg: DynamicCfg,
      replyTo: ActorRef[DynamicConfigOpResult]
  ) extends Command {

    def topicKey: Option[String] = dynamicCfgTopicKey(cfg)

  }

  final case class Remove(
      topicKey: String,
      replyTo: ActorRef[DynamicConfigOpResult]
  ) extends Command

  final case class RemoveAll(
      replyTo: ActorRef[DynamicConfigOpResult]
  ) extends Command

  final case class GetAll(
      replyTo: ActorRef[DynamicConfigOpResult]
  ) extends Command

  final case class FindConfig(
      key: String,
      replyTo: ActorRef[DynamicConfigOpResult]
  ) extends Command

  /*
      Messages received from [[DynamicConfigConsumer]]
   */

  sealed private[config] trait InternalCommand extends DynamicConfigProtocol {
    val key: String
    val offset: Long
  }

  final private[config] case class UpdateDynamicConfigRecord(
      key: String,
      value: DynamicCfg,
      offset: Long
  ) extends InternalCommand

  final private[config] case class RemoveDynamicConfigRecord(
      key: String,
      offset: Long
  ) extends InternalCommand

}
