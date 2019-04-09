package net.scalytica.kafka.wsproxy.consumer

import akka.actor.testkit.typed.CapturedLogEvent
import akka.actor.testkit.typed.Effect._
import akka.actor.testkit.typed.scaladsl.BehaviorTestKit
import akka.actor.testkit.typed.scaladsl.TestInbox
import akka.actor.typed._
import akka.actor.typed.scaladsl._
import akka.event.Logging
import akka.kafka.testkit.ConsumerResultFactory
import org.scalatest.{BeforeAndAfter, MustMatchers, WordSpec}
import net.scalytica.kafka.wsproxy.consumer.CommitHandler._
import net.scalytica.kafka.wsproxy.models.{
  ConsumerKeyValueRecord,
  Formats,
  Partition
}
import net.scalytica.kafka.wsproxy.models.ValueDetails.OutValueDetails

class CommitHandlerSpec extends WordSpec with MustMatchers with BeforeAndAfter {

  private[this] def createKeyValueRecord(
      groupId: String,
      topic: String,
      partition: Int,
      offset: Long,
      timestamp: Long
  ) = {
    ConsumerKeyValueRecord(
      topic = topic,
      partition = partition,
      offset = offset,
      timestamp = timestamp,
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
          metadata = null // scalastyle:off
        )
      )
    )
  }

  "The CommitHandler" should {

    "add a message to the stack" in {
      val tk = BehaviorTestKit(commitStack)
      val rec =
        createKeyValueRecord("grp1", "topic1", 0, 0, System.currentTimeMillis())
      val stashCmd = Stash(rec)
      val inbox    = TestInbox[Stack]()

      // send stash command
      tk.run(stashCmd)
      // ask for updated stack
      tk.run(GetStack(inbox.ref))
      inbox.expectMessage(
        Map(
          Partition(0) -> List(
            Uncommitted(rec.wsProxyMessageId, rec.committableOffset.get)
          )
        )
      )
    }

    "add messages to partition specific stacks" in {
      pending
    }

    "optionally auto commit and drop messages older than a given age" in {
      pending
    }

    "drop the oldest messages in the stack when max size is reached" in {
      pending
    }

    "accept a WsCommit command, commit the message and clean up the stack" in {
      pending
    }

    "do nothing if the WsCommit message references a non-existing message" in {
      pending
    }

  }

}
