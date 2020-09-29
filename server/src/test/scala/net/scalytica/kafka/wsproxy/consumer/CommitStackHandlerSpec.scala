package net.scalytica.kafka.wsproxy.consumer

import akka.actor.testkit.typed.scaladsl.{BehaviorTestKit, TestInbox}
import akka.kafka.testkit.ConsumerResultFactory
import net.scalytica.kafka.wsproxy.consumer.CommitStackHandler._
import net.scalytica.kafka.wsproxy.consumer.CommitStackTypes._
import net.scalytica.kafka.wsproxy.models.ValueDetails.OutValueDetails
import net.scalytica.kafka.wsproxy.models._
import net.scalytica.test.WsProxyKafkaSpec
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Minute, Span}
import org.scalatest.BeforeAndAfter

import scala.collection.immutable
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec

class CommitStackHandlerSpec
    extends AnyWordSpec
    with Matchers
    with BeforeAndAfter
    with Eventually
    with WsProxyKafkaSpec {

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(1, Minute))

  private[this] def createKeyValueRecord(
      groupId: String,
      topic: String,
      partition: Int,
      offset: Long,
      timestamp: Long
  ) = {
    ConsumerKeyValueRecord(
      topic = TopicName(topic),
      partition = Partition(partition),
      offset = Offset(offset),
      timestamp = Timestamp(timestamp),
      headers = None,
      key = OutValueDetails[String](
        value = s"$topic-$partition-$offset",
        format = Some(Formats.StringType)
      ),
      value = OutValueDetails[String](
        value = s"$topic-$partition-$offset",
        format = Some(Formats.StringType)
      ),
      committableOffset = Some(
        ConsumerResultFactory.committableOffset(
          groupId = groupId,
          topic = topic,
          partition = partition,
          offset = offset,
          metadata = null // scalastyle:ignore
        )
      )
    )
  }

  private[this] def validateCommitStack(
      recs: immutable.Seq[ConsumerKeyValueRecord[String, String]],
      removeIds: Option[Seq[WsMessageId]] = None
  )(
      implicit tk: BehaviorTestKit[CommitProtocol],
      inbox: TestInbox[CommitStack]
  ): TestInbox[CommitStack] = {
    val fullStack =
      recs.foldLeft(CommitStack.empty) { case (s, r) =>
        val uc = Uncommitted(r.wsProxyMessageId, r.committableOffset.get)
        s.get(r.partition)
          .map(u => s.updated(r.partition, u :+ uc))
          .getOrElse(s + (r.partition -> SubStack(uc)))
      }

    val stack = removeIds
      .map { remIds =>
        fullStack.stack.map { case (p, m) =>
          val rem = m.entries.filterNot(u => remIds.contains(u.wsProxyMsgId))
          p -> SubStack(rem)
        }
      }
      .map(CommitStack.apply)
      .getOrElse(fullStack)

    tk.run(GetStack(inbox.ref))
    inbox.expectMessage(stack)
  }

  "The CommitHandler" should {

    "add a message to the stack" in {
      implicit val testCfg = defaultTestAppCfg
      implicit val tk      = BehaviorTestKit(commitStack)
      implicit val inbox   = TestInbox[CommitStack]()

      val rec =
        createKeyValueRecord("grp1", "topic1", 0, 0, System.currentTimeMillis())

      val stashCommands = Stash(rec)
      // send stash command
      tk.run(stashCommands)
      // ask for updated stack
      validateCommitStack(immutable.Seq(rec))
    }

    "add messages from different partitions to the stack" in {
      implicit val testCfg = defaultTestAppCfg
      implicit val tk      = BehaviorTestKit(commitStack)
      implicit val inbox   = TestInbox[CommitStack]()

      val recs = 0 until 5 map { i =>
        createKeyValueRecord("grp1", "topic1", i, 0, System.currentTimeMillis())
      }
      val stashCommands = recs.map(Stash.apply)
      // send stash commands
      stashCommands.foreach(cmd => tk.run(cmd))
      // ask for updated stack
      validateCommitStack(recs)
    }

    "optionally auto commit and drop messages older than a given age" in {
      // Not yet implemented
      pending
    }

    "drop the oldest messages for a partition from the stack when the max" +
      " size is reached" in {
        implicit val testCfg = defaultTestAppCfg.copy(
          commitHandler = defaultTestAppCfg.commitHandler.copy(maxStackSize = 3)
        )
        implicit val tk    = BehaviorTestKit(commitStack)
        implicit val inbox = TestInbox[CommitStack]()

        val stackSize = testCfg.commitHandler.maxStackSize

        val recs =
          0 until 3 map { p =>
            0 until 20 map { i =>
              createKeyValueRecord(
                groupId = "grp1",
                topic = "topic1",
                partition = p,
                offset = i.toLong,
                timestamp = System.currentTimeMillis()
              )
            }
          }

        val insert  = recs.flatten
        val removed = recs.flatMap(_.dropRight(stackSize))

        insert.foreach(cmd => tk.run(Stash(cmd)))
        validateCommitStack(insert, Some(removed.map(_.wsProxyMessageId)))
      }

    "accept a WsCommit command, commit the message and clean up the stack" in {
      implicit val testCfg = defaultTestAppCfg
      implicit val tk      = BehaviorTestKit(commitStack)
      implicit val inbox   = TestInbox[CommitStack]()

      val recs = 0 until 3 map { i =>
        createKeyValueRecord("grp1", "topic1", i, 0, System.currentTimeMillis())
      }

      recs.foreach(cmd => tk.run(Stash(cmd)))
      validateCommitStack(recs)

      tk.run(Commit(WsCommit(recs.head.wsProxyMessageId)))
      validateCommitStack(recs, Some(Seq(recs.head.wsProxyMessageId)))
    }

    "do nothing if the WsCommit message references a non-existing message" in {
      implicit val testCfg = defaultTestAppCfg
      implicit val tk      = BehaviorTestKit(commitStack)
      implicit val inbox   = TestInbox[CommitStack]()

      val recs = 0 until 3 map { i =>
        createKeyValueRecord("grp1", "topic1", i, 0, System.currentTimeMillis())
      }
      val bogusId = WsMessageId(
        topic = TopicName("topic1"),
        partition = Partition(2),
        offset = Offset(1),
        timestamp = recs(2).timestamp
      )

      recs.foreach(cmd => tk.run(Stash(cmd)))
      validateCommitStack(recs)

      tk.run(Commit(WsCommit(bogusId)))
      validateCommitStack(recs)
    }

  }

}
