package net.scalytica.kafka.wsproxy.consumer

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.kafka.ConsumerMessage.Committable
import com.typesafe.scalalogging.Logger
import net.scalytica.kafka.wsproxy.models.{
  Partition,
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
  private[consumer] case class Uncommitted(
      wsProxyMsgId: WsMessageId,
      committable: Committable
  )

  /*
     Type aliases intended to make clear that the CommitHandler behaviour is
     using a kind of Stack to keep track of uncommitted messages. The stack is
     in effect a regular list, where new messages are appended at the end.
     And, whenever a commit message comes along, the list is drained from the
     beginning until the WsMessageId of the incoming message is found in the
     list. If the WsMessageId is NOT found, the stack is left as-is and no
     commit is executed.
   */
  private[consumer] type SubStack = List[Uncommitted]
  private[consumer] type Stack    = Map[Partition, SubStack]

  private[this] val EmptyStack: Stack = Map.empty

  /** ADT defining the valid protocol for the [[CommitHandler]] */
  sealed trait Protocol

  case class Stash(record: WsConsumerRecord[_, _]) extends Protocol
  case class Commit(commit: WsCommit)              extends Protocol
  case object Continue                             extends Protocol
  case object Stop                                 extends Protocol
  case class GetStack(sender: ActorRef[Stack])     extends Protocol

  /** Behaviour initialising the message commit stack */
  val commitStack: Behavior[Protocol] = committableStack()

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
  )(implicit ctx: ActorContext[Protocol]): Option[Stack] = {
    def addToStack(partition: Partition, uncommitted: Uncommitted): Stack = {
      stack
        .find(_._1 == partition)
        .map { _ =>
          stack
            .get(partition)
            .map(pStack => stack.updated(partition, pStack :+ uncommitted))
            .getOrElse(stack)
        }
        .getOrElse(stack + (partition -> List(uncommitted)))
    }

    rec.committableOffset.map { co =>
      // Append the uncommitted message at the END of the stack
      val next =
        addToStack(rec.partition, Uncommitted(rec.wsProxyMessageId, co))
      ctx.log.debug(s"STASHED ${rec.wsProxyMessageId} to stack")
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
      implicit
      ec: ExecutionContext,
      ctx: ActorContext[Protocol]
  ): Option[Stack] = {
    stack
      .find(_._2.exists(_.wsProxyMsgId == msgId))
      .map {
        case (p, s) =>
          val reduced = s.dropWhile(_.wsProxyMsgId != msgId).tail
          stack.updated(p, reduced) -> reduced
      }
      .flatMap {
        case (nextStack, reducedStack) =>
          reducedStack.headOption.map { u =>
            u.committable.commitScaladsl().foreach { _ =>
              ctx.log.debug(s"COMMITTED $msgId and cleaned up stack")
            }
            nextStack
          }
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
  private[this] def committableStack(
      stack: Stack = EmptyStack
  ): Behavior[Protocol] =
    Behaviors.setup { implicit ctx =>
      implicit val ec = implicitly(ctx.executionContext)
      Behaviors.receiveMessage {
        case Stash(record) =>
          stash(stack, record).map(committableStack).getOrElse(Behaviors.same)

        case Commit(wsc) =>
          commit(stack, wsc.wsProxyMessageId)
            .map(committableStack)
            .getOrElse(Behaviors.same)

        case Continue =>
          Behaviors.same

        case GetStack(to) =>
          to ! stack
          Behaviors.same

        case Stop =>
          Behaviors.stopped
      }
    }
}
