package net.scalytica.kafka.wsproxy.admin

import io.github.embeddedkafka.EmbeddedKafka
import net.scalytica.kafka.wsproxy.codecs.BasicSerdes.StringDeserializer
import net.scalytica.kafka.wsproxy.errors.TopicNotFoundError
import net.scalytica.kafka.wsproxy.models.{
  PartitionOffsetMetadata,
  TopicName,
  WsClientId,
  WsGroupId
}
import net.scalytica.test.TestDataGenerators.createPartitionOffsetMetadataList
import net.scalytica.test.{WsProxySpec, WsReusableProxyKafkaFixture}
import org.apache.kafka.common.ConsumerGroupState
import org.scalatest.Inspectors.forAll
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.time.{Minutes, Span}
import org.scalatest.{Assertion, OptionValues}
import org.scalatest.wordspec.AnyWordSpec

import java.time.Duration
import scala.jdk.CollectionConverters._

// scalastyle:off magic.number
class WsKafkaAdminClientSpec
    extends AnyWordSpec
    with OptionValues
    with ScalaFutures
    with Eventually
    with WsProxySpec
    with WsReusableProxyKafkaFixture
    with EmbeddedKafka {

  override protected val testTopicPrefix: String = "kafka-admin-test-topic"

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(2, Minutes))

  "The WsKafkaAdminClient" should {

    "return info on brokers in the cluster" in
      withNoContext() { case (kCfg, appCfg) =>
        val admin = new WsKafkaAdminClient(appCfg)

        val res = admin.clusterInfo
        res must have size 1
        res.headOption.value.id mustBe 0
        res.headOption.value.host mustBe "localhost"
        res.headOption.value.port mustBe kCfg.kafkaPort
        res.headOption.value.rack mustBe None

        admin.close()
      }

    "return number replicas to use for the session topic" in
      withNoContext() { case (_, appCfg) =>
        val admin = new WsKafkaAdminClient(appCfg)

        admin.replicationFactor(
          appCfg.sessionHandler.topicName,
          appCfg.sessionHandler.topicReplicationFactor
        ) mustBe 1

        admin.close()
      }

    "return number replicas to use for the dynamic config topic" in
      withNoContext() { case (_, appCfg) =>
        val admin = new WsKafkaAdminClient(appCfg)

        admin.replicationFactor(
          appCfg.dynamicConfigHandler.topicName,
          appCfg.dynamicConfigHandler.topicReplicationFactor
        ) mustBe 1

        admin.close()
      }

    "number of replicas to use must not exceed number of brokers" in {
      withNoContext() { case (_, appCfg) =>
        val admin = new WsKafkaAdminClient(appCfg)
        admin.replicationFactor(TopicName("my-cool-topic"), 3) mustBe 1
      }
    }

    "create and find the session state topic" in
      withNoContext() { case (_, appCfg) =>
        val admin = new WsKafkaAdminClient(appCfg)

        admin.initSessionStateTopic()

        val res = admin.findTopic(appCfg.sessionHandler.topicName)
        res must not be empty
        res.value mustBe appCfg.sessionHandler.topicName.value

        admin.close()
      }

    "create and find the dynamic config topic" in
      withNoContext() { case (_, appCfg) =>
        val admin = new WsKafkaAdminClient(appCfg)

        admin.initDynamicConfigTopic()

        val res = admin.findTopic(appCfg.dynamicConfigHandler.topicName)
        res must not be empty
        res.value mustBe appCfg.dynamicConfigHandler.topicName.value

        admin.close()
      }

    "return the number of partitions for the given topic" in
      withNoContext() { case (_, appCfg) =>
        val admin = new WsKafkaAdminClient(appCfg)
        val topic = TopicName("fifafum")
        admin.createTopic(topic, 3).isSuccess mustBe true

        val res = admin.numTopicPartitions(topic)
        res mustBe 3
      }

    "throw TopicNotFoundError fetching num partitions for unknown topic" in
      withNoContext() { case (_, appCfg) =>
        val admin = new WsKafkaAdminClient(appCfg)
        assertThrows[TopicNotFoundError] {
          admin.numTopicPartitions(TopicName("nobody-home"))
        }
      }

    "return a list of topic partition info for a given topic" in
      withNoContext() { case (_, appCfg) =>
        val admin = new WsKafkaAdminClient(appCfg)
        val topic = TopicName("foo")
        admin.createTopic(topic, 3).isSuccess mustBe true

        val res = admin.topicPartitionInfoList(topic)
        res.size mustBe 3
      }

    "throw TopicNotFoundError fetching list of partitions for unkonw topic" in
      withNoContext() { case (_, appCfg) =>
        val admin = new WsKafkaAdminClient(appCfg)
        assertThrows[TopicNotFoundError] {
          admin.topicPartitionInfoList(TopicName("bar"))
        }
      }

    "return None when trying to describe a non-existing topic" in
      withNoContext() { case (_, appCfg) =>
        val admin = new WsKafkaAdminClient(appCfg)

        admin.describeTopic(TopicName("foobar")) mustBe None
      }

    "describe a topic" in withConsumerContext(
      topic = "test-topic-0",
      numMessages = 0,
      partitions = 5
    ) { cctx =>
      val admin = new WsKafkaAdminClient(cctx.appCfg)

      val desc = admin.describeTopic(cctx.topicName.value).value
      desc.name mustBe cctx.topicName.value
      desc.numPartitions mustBe 5
    }

    "list all consumer groups" in
      withConsumerContext(
        topic = nextTopic,
        numMessages = 15,
        partitions = 3
      ) { cctx =>
        val admin       = new WsKafkaAdminClient(cctx.appCfg)
        val subscribeTo = cctx.topicName.toList.map(_.value).asJavaCollection

        val c1 = kafkaConsumer(WsGroupId("test-group-a"), WsClientId("c1"))
        val c2 = kafkaConsumer(WsGroupId("test-group-b"), WsClientId("c2"))

        c1.subscribe(subscribeTo)
        c2.subscribe(subscribeTo)

        eventually {
          val p1 = c1.poll(Duration.ofMillis(100L))
          p1.asScala must not be empty
          c1.commitSync()
        }
        eventually {
          val p2 = c2.poll(Duration.ofMillis(100L))
          p2.asScala must not be empty
          c2.commitSync()
        }

        val res = admin.listConsumerGroups(includeInternals = true)
        res must have size 3

        c1.close()
        c2.close()
      }

    "list all non-internal consumer groups" in
      withConsumerContext(
        topic = nextTopic,
        numMessages = 15,
        partitions = 3
      ) { cctx =>
        val admin       = new WsKafkaAdminClient(cctx.appCfg)
        val subscribeTo = cctx.topicName.toList.map(_.value).asJavaCollection

        val c1 = kafkaConsumer(WsGroupId("test-group-c"), WsClientId("c3"))
        val c2 = kafkaConsumer(WsGroupId("test-group-d"), WsClientId("c4"))

        c1.subscribe(subscribeTo)
        c2.subscribe(subscribeTo)

        eventually {
          val p1 = c1.poll(Duration.ofMillis(100L))
          p1.asScala must not be empty
          c1.commitSync()
        }
        eventually {
          val p2 = c2.poll(Duration.ofMillis(100L))
          p2.asScala must not be empty
          c2.commitSync()
        }

        val res = admin.listConsumerGroups()
        res must have size 2
        forAll(res) { cg =>
          cg.groupId.value must startWith("test-group")
        }

        c1.close()
        c2.close()
      }

    "list all active consumer groups" in
      withConsumerContext(
        topic = nextTopic,
        numMessages = 15,
        partitions = 3
      ) { cctx =>
        val admin       = new WsKafkaAdminClient(cctx.appCfg)
        val subscribeTo = cctx.topicName.toList.map(_.value).asJavaCollection

        val c1 = kafkaConsumer(WsGroupId("test-group-e"), WsClientId("c5"))
        val c2 = kafkaConsumer(WsGroupId("test-group-f"), WsClientId("c6"))

        c1.subscribe(subscribeTo)
        c2.subscribe(subscribeTo)

        eventually {
          val p1 = c1.poll(Duration.ofMillis(100L))
          p1.asScala must not be empty
          c1.commitSync()
        }
        eventually {
          val p2 = c2.poll(Duration.ofMillis(100L))
          p2.asScala must not be empty
          c2.commitSync()
        }

        c1.close()

        val res =
          admin.listConsumerGroups(activeOnly = true, includeInternals = true)
        res.size mustBe 2

        c2.close()
      }

    "list all active non-internal consumer groups" in
      withConsumerContext(
        topic = nextTopic,
        numMessages = 15,
        partitions = 3
      ) { cctx =>
        val admin       = new WsKafkaAdminClient(cctx.appCfg)
        val subscribeTo = cctx.topicName.toList.map(_.value).asJavaCollection

        val c1 = kafkaConsumer(WsGroupId("test-group-g"), WsClientId("c7"))
        val c2 = kafkaConsumer(WsGroupId("test-group-h"), WsClientId("c8"))

        c1.subscribe(subscribeTo)
        c2.subscribe(subscribeTo)

        eventually {
          val p1 = c1.poll(Duration.ofMillis(100L))
          p1.asScala must not be empty
          c1.commitSync()
        }
        eventually {
          val p2 = c2.poll(Duration.ofMillis(100L))
          p2.asScala must not be empty
          c2.commitSync()
        }

        c1.close()

        val res =
          admin.listConsumerGroups(activeOnly = true)

        res.size mustBe 1

        c2.close()
      }

    "describe a given consumer group" in withConsumerContext(
      topic = nextTopic,
      numMessages = 15,
      partitions = 3
    ) { cctx =>
      implicit val admin: WsKafkaAdminClient =
        new WsKafkaAdminClient(cctx.appCfg)

      val grpId       = WsGroupId("describe-consumer-group")
      val subscribeTo = cctx.topicName.toList.map(_.value).asJavaCollection

      val c1 = kafkaConsumer(grpId, WsClientId("c9"))
      c1.subscribe(subscribeTo)

      eventually {
        val p1 = c1.poll(Duration.ofMillis(100L))
        p1.asScala must not be empty
      }

      getAndAssertConsumerGroup(grpId, ConsumerGroupState.STABLE)
    }

    "alter offsets for a given consumer group" in
      withConsumerContext(
        topic = nextTopic,
        numMessages = 60,
        partitions = 3
      ) { cctx =>
        val grpId = WsGroupId("alter-offset-group")
        implicit val admin: WsKafkaAdminClient =
          new WsKafkaAdminClient(cctx.appCfg)
        val topic   = cctx.topicName.value
        val offsets = createPartitionOffsetMetadataList(topic)

        // Initial check of consumer group
        getAndAssertConsumerGroup(grpId, ConsumerGroupState.DEAD)
        listAndAssertOffsets(grpId)

        // Change the consumer group offsets
        admin.alterConsumerGroupOffsets(grpId, offsets)

        // Re-check the consumer group
        getAndAssertConsumerGroup(grpId, ConsumerGroupState.EMPTY)
        // Verify that the offsets have changed
        listAndAssertOffsets(grpId, offsets)
      }

    "delete offsets for a given consumer group" in withConsumerContext(
      topic = nextTopic,
      numMessages = 15,
      partitions = 3
    ) { cctx =>
      implicit val admin: WsKafkaAdminClient =
        new WsKafkaAdminClient(cctx.appCfg)

      val grpId       = WsGroupId("delete-offsets-group")
      val subscribeTo = cctx.topicName.toList.map(_.value).asJavaCollection

      val c1 = kafkaConsumer(grpId, WsClientId("c10"))
      c1.subscribe(subscribeTo)

      eventually {
        val p1 = c1.poll(Duration.ofMillis(100L))
        p1.asScala must not be empty
      }
      c1.commitSync()
      c1.close()

      val o1 = admin.listConsumerGroupOffsets(grpId)
      o1 must not be empty

      admin.deleteConsumerGroupOffsets(grpId, cctx.topicName.value)

      val o2 = admin.listConsumerGroupOffsets(grpId)
      o2 mustBe empty
    }
  }

  private[this] def listAndAssertOffsets(
      groupId: WsGroupId,
      expected: List[PartitionOffsetMetadata] = List.empty
  )(implicit admin: WsKafkaAdminClient): Assertion = {
    val offsets = admin.listConsumerGroupOffsets(groupId)
    offsets.size mustBe expected.size
    offsets must contain allElementsOf offsets
  }

  private[this] def getAndAssertConsumerGroup(
      groupId: WsGroupId,
      state: ConsumerGroupState
  )(implicit admin: WsKafkaAdminClient): Assertion = {
    val cg = admin.describeConsumerGroup(groupId)
    cg.value.groupId mustBe groupId
    cg.value.state.value mustBe state.name()
  }

}
