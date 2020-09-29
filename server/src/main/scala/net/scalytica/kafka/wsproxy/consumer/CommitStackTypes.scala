package net.scalytica.kafka.wsproxy.consumer

import akka.kafka.CommitterSettings
import akka.kafka.ConsumerMessage.Committable
import akka.kafka.scaladsl.Committer
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import com.typesafe.scalalogging.Logger
import net.scalytica.kafka.wsproxy.Configuration.AppCfg
import net.scalytica.kafka.wsproxy.models.{
  Partition,
  WsConsumerRecord,
  WsMessageId
}

/**
 * Types that model a kind of Stack to keep track of uncommitted messages. The
 * stack is in effect a regular list, where new messages are appended at the
 * end. And, whenever a commit message comes along, the list is drained from the
 * beginning until the WsMessageId of the incoming message is found in the list.
 * If the WsMessageId is NOT found, the stack is left as-is and no commit is
 * executed.
 */
private[consumer] object CommitStackTypes {

  private[this] val logger = Logger(getClass)

  /**
   * Carries necessary metadata for messages in the stack.
   *
   * @param wsProxyMsgId
   *   the unique [[WsMessageId]] for the committable message
   * @param committable
   *   the [[Committable]] message
   */
  case class Uncommitted(
      wsProxyMsgId: WsMessageId,
      committable: Committable
  )

  case class SubStack(entries: List[Uncommitted] = List.empty) {

    def dropOldest()(implicit cfg: AppCfg): SubStack = {
      if (entries.size == cfg.commitHandler.maxStackSize)
        SubStack(entries.drop(1))
      else this
    }

    def exists(cond: Uncommitted => Boolean): Boolean = entries.exists(cond)

    def dropWhile(cond: Uncommitted => Boolean): SubStack =
      SubStack(entries.dropWhile(cond))

    def tail: SubStack = SubStack(entries.tail)

    def headOption: Option[Uncommitted] = entries.headOption

    def :+(uc: Uncommitted): SubStack = SubStack(entries :+ uc)
  }

  object SubStack {
    def apply(u: Uncommitted): SubStack = SubStack(List(u))
  }

  case class CommitStack(stack: Map[Partition, SubStack] = Map.empty) {

    def +(kv: (Partition, SubStack)): CommitStack = CommitStack(stack + kv)

    def updated(p: Partition, sub: SubStack): CommitStack =
      CommitStack(stack.updated(p, sub))

    def get(p: Partition): Option[SubStack] = stack.get(p)

    private[this] def addToStack(
        partition: Partition,
        uncommitted: Uncommitted
    )(implicit cfg: AppCfg): CommitStack = {
      val nextStack: CommitStack = stack
        .find(_._1 == partition)
        .map { _ =>
          stack
            .get(partition)
            .map { partitionSubStack =>
              val subStack = partitionSubStack.dropOldest() :+ uncommitted
              val ns       = updated(partition, subStack)
              logger.debug(
                s"STASH: stashed ${uncommitted.wsProxyMsgId} to sub-stack" +
                  s" for partition ${partition.value}"
              )
              ns
            }
            .getOrElse(this)
        }
        .getOrElse {
          logger.debug(
            s"STASH: stashed ${uncommitted.wsProxyMsgId} to NEW sub-stack" +
              s" for partition ${partition.value}"
          )
          this + (partition -> SubStack(uncommitted))
        }
      logger.debug(s"STASH: Next stack is: " + stack.mkString(","))
      nextStack
    }

    /**
     * Adds a new [[Uncommitted]] entry to the [[CommitStack]]
     *
     * @param record
     *   the [[WsConsumerRecord]] to derive an [[Uncommitted]] from.
     * @return
     *   An option with the new stack or empty if no offset was found.
     */
    def stash(
        record: WsConsumerRecord[_, _]
    )(implicit cfg: AppCfg): Option[CommitStack] =
      record.committableOffset.map { co =>
        addToStack(record.partition, Uncommitted(record.wsProxyMessageId, co))
      }

    /**
     * Tries to locate a message with the provided [[WsMessageId]] to commit it
     * to Kafka. All messages that are OLDER than the message to commit are
     * DROPPED from the stack, since they are no longer necessary to commit.
     *
     * @param msgId
     *   the [[WsMessageId]] of the message to commit.
     * @return
     *   An option with updated stack or empty if message wasn't found.
     */
    def commit(
        msgId: WsMessageId
    )(
        implicit mat: Materializer,
        cs: CommitterSettings
    ): Option[CommitStack] =
      stack
        .find(_._2.exists(_.wsProxyMsgId == msgId))
        .orElse {
          logger.debug(s"COMMIT: Could not find $msgId in stack")
          None
        }
        .map { case (p, s) =>
          val reduced = s.dropWhile(_.wsProxyMsgId != msgId)
          updated(p, reduced.tail) -> reduced.headOption
        }
        .flatMap { case (nextStack, maybeCommittable) =>
          maybeCommittable.map { u =>
            Source
              .single(u)
              .map(_.committable)
              .via(Committer.flow(cs))
              .runForeach { _ =>
                logger.debug(s"COMMIT: committed $msgId and cleaned up stack")
              }
            nextStack
          }
        }

    def mkString: String = stack.mkString("")

    def mkString(sep: String): String = stack.mkString(sep)

    def mkString(start: String, sep: String, end: String): String =
      stack.mkString(start, sep, end)

  }

  object CommitStack {

    lazy val empty = CommitStack()

  }
}
