package net.scalytica.kafka.wsproxy.consumer

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import akka.kafka.CommitterSettings
import akka.stream.Materializer
import net.scalytica.kafka.wsproxy.config.Configuration.AppCfg
import net.scalytica.kafka.wsproxy.consumer.CommitStackTypes._
import net.scalytica.kafka.wsproxy.models.{WsCommit, WsConsumerRecord}

object CommitStackHandler {

  /** ADT defining the valid protocol for the [[CommitStackHandler]] */
  sealed trait CommitProtocol

  case class Stash(record: WsConsumerRecord[_, _])   extends CommitProtocol
  case class Commit(commit: WsCommit)                extends CommitProtocol
  case object Continue                               extends CommitProtocol
  case object Stop                                   extends CommitProtocol
  case class GetStack(sender: ActorRef[CommitStack]) extends CommitProtocol

  /** Behaviour initialising the message commit stack */
  def commitStack(
      implicit cfg: AppCfg,
      mat: Materializer
  ): Behavior[CommitProtocol] = {
    implicit val cs = CommitterSettings.create(mat.system)
    committableStack()
  }

  /**
   * Behavior implementation for the CommitHandler. New [[Uncommitted]] messages
   * are appended at the end of the [[CommitStack]]. Whenever a [[Commit]] is
   * received, the stack is flushed of all messages that are older than the
   * message being committed. This way the number of messages in the stack can
   * be somewhat controlled.
   *
   * @param stack
   *   the message stack of [[Uncommitted]] messages
   * @return
   *   a Behavior describing the [[CommitStackHandler]].
   */
  private[this] def committableStack(
      stack: CommitStack = CommitStack.empty
  )(
      implicit cfg: AppCfg,
      cs: CommitterSettings,
      mat: Materializer
  ): Behavior[CommitProtocol] =
    Behaviors.setup { implicit ctx =>
      Behaviors.receiveMessage {
        case Stash(record) =>
          stack.stash(record).map(committableStack).getOrElse(Behaviors.same)

        case Commit(wsc) =>
          stack
            .commit(wsc.wsProxyMessageId)
            .map(committableStack)
            .getOrElse(Behaviors.same)

        case Continue =>
          Behaviors.same

        case GetStack(to) =>
          ctx.log.debug("Current stack is:\n" + stack.mkString("\n"))
          to ! stack
          Behaviors.same

        case Stop =>
          ctx.log.debug(s"Received stop message")
          Behaviors.stopped
      }
    }
}
