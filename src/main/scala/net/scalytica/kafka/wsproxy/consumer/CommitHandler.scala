package net.scalytica.kafka.wsproxy.consumer

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.kafka.ConsumerMessage.Committable
import com.typesafe.scalalogging.Logger
import net.scalytica.kafka.wsproxy.records.{
  WsCommit,
  WsConsumerRecord,
  WsMessageId
}

import scala.concurrent.ExecutionContext

object CommitHandler {

  private[this] val logger = Logger(getClass)

  /**
   * Carries necessary metadata for messages in the stack.
   */
  private case class Uncommitted(
      wsProxyMsgId: WsMessageId,
      committable: Committable
  )

  /**
   * Type alias intended to make clear that the CommitHandler behaviour is
   * using a kind of Stack to keep track of uncommitted messages. The stack is
   * in effect a regular list, where new messages are appended at the end.
   * And, whenever a commit message comes along, the list is drained from the
   * beginning until the WsMessageId of the incoming message is found in the
   * list. If the WsMessageId is NOT found, the stack is left as-is and no
   * commit is executed.
   */
  private[this] type Stack = List[Uncommitted]

  /** ADT defining the valid protocol for the [[CommitHandler]] */
  sealed trait Protocol

  case class Stash(record: WsConsumerRecord[_, _]) extends Protocol
  case class Commit(commit: WsCommit)              extends Protocol
  case object Continue                             extends Protocol
  case object Stop                                 extends Protocol

  /** Behaviour initialising the message commit stack */
  val commitStack: Behavior[Protocol] = committableStack(List.empty)

  /**
   * Adds a new [[Uncommitted]] entry to the [[Stack]]
   *
   * @param stack the stack to add the [[Uncommitted]] data to
   * @param rec the [[WsConsumerRecord]] to derive an [[Uncommitted]] from.
   * @return An option with the new stack or empty if no offset was found.
   */
  private[this] def stash(
      stack: Stack,
      rec: WsConsumerRecord[_, _]
  ): Option[Stack] = {
    rec.committableOffset.map { co =>
      // Append the uncommitted message at the END of the stack
      val next = stack :+ Uncommitted(rec.wsProxyMessageId, co)
      logger.debug(s"STASHED ${rec.wsProxyMessageId} to stack")
      next
    }
  }

  /**
   * Tries to locate a message with the provided [[WsMessageId]] to commit it to
   * Kafka. All messages that are OLDER than the message to commit are DROPPED
   * from the stack, since they are no longer necessary to commit.
   *
   * @param stack the [[Stack]] of [[Uncommitted]] messages.
   * @param msgId the [[WsMessageId]] of the message to commit.
   * @return An option with an updated stack or empty if message wasn't found.
   */
  private[this] def commit(stack: Stack, msgId: WsMessageId)(
      implicit ec: ExecutionContext
  ): Option[Stack] = {
    val reduced = stack.dropWhile(_.wsProxyMsgId != msgId)
    reduced.headOption.map { u =>
      u.committable.commitScaladsl().foreach { _ =>
        logger.debug(s"COMMITTED $msgId and cleaned up stack")
      }
      reduced.tail
    }
  }

  /**
   * Behavior implementation for the CommitHandler. The [[Stack]] is implemented
   * using a regular List, where new [[Uncommitted]] messages are appended at
   * the end. Whenever a [[Commit]] is received, the stack is flushed of all
   * messages that are older than the message being committed. This way the
   * number of messages in the stack can be somewhat controlled.
   *
   * @param stack the message stack of [[Uncommitted]] messages
   * @return a Behavior describing the [[CommitHandler]].
   */
  private[this] def committableStack(stack: Stack): Behavior[Protocol] =
    Behaviors.setup { ctx =>
      implicit val ec = ctx.executionContext
      Behaviors.receiveMessage {
        case Stash(record) =>
          stash(stack, record).map(committableStack).getOrElse(Behaviors.same)

        case Commit(wsc) =>
          commit(stack, wsc.wsProxyMessageId)
            .map(committableStack)
            .getOrElse(Behaviors.same)

        case Continue =>
          Behaviors.same

        case Stop =>
          Behaviors.stopped
      }
    }
}
