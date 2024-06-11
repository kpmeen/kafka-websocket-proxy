package net.scalytica.kafka.wsproxy.models

import net.scalytica.kafka.wsproxy.StringExtensions
import net.scalytica.kafka.wsproxy.models.ConsumerGroup.{
  ActiveStates,
  InactiveStates
}
import org.apache.kafka.clients.admin.{
  ConsumerGroupDescription,
  ConsumerGroupListing
}
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.common.{ConsumerGroupState, TopicPartition}

import scala.jdk.OptionConverters._
import scala.jdk.CollectionConverters._

case class ConsumerGroup(
    groupId: WsGroupId,
    isSimple: Boolean,
    state: Option[String],
    partitionAssignor: Option[String] = None,
    members: Seq[WsClientId] = Seq.empty
) {

  /**
   * Function to check the state of the [[ConsumerGroup]].
   *
   * It is important to note that '''only''' state that can be determined to be
   * active with 100% certainty will be interpreted as an '''active''' group.
   * See the list of states that qualify for an active/inactive group from the
   * perspective of the Kafka WebSocket Proxy.
   *
   * The state is considered active if any one of the following states are set:
   *   - [[ConsumerGroupState.STABLE]]
   *   - [[ConsumerGroupState.PREPARING_REBALANCE]]
   *   - [[ConsumerGroupState.COMPLETING_REBALANCE]]
   *
   * If the state is {{{None}}} or any of the following values, the
   * [[ConsumerGroup]] is considered to be inactive:
   *   - {{{None}}}
   *   - [[ConsumerGroupState.DEAD]]
   *   - [[ConsumerGroupState.EMPTY]]
   *   - [[ConsumerGroupState.UNKNOWN]]
   * @return
   *   true if active, else false
   */
  def isActive: Boolean = {
    state.exists(s => ActiveStates.contains(ConsumerGroupState.valueOf(s)))
  }

  def isInactive: Boolean = {
    state.exists(s => InactiveStates.contains(ConsumerGroupState.valueOf(s)))
  }

}

object ConsumerGroup {

  val ActiveStates: Set[ConsumerGroupState] = Set(
    ConsumerGroupState.PREPARING_REBALANCE,
    ConsumerGroupState.COMPLETING_REBALANCE,
    ConsumerGroupState.STABLE
  )
  val InactiveStates: Set[ConsumerGroupState] = Set(
    ConsumerGroupState.DEAD,
    ConsumerGroupState.EMPTY,
    ConsumerGroupState.UNKNOWN
  )

  def fromKafkaListing(cgl: ConsumerGroupListing): ConsumerGroup = {
    ConsumerGroup(
      groupId = WsGroupId.apply(cgl.groupId()),
      isSimple = cgl.isSimpleConsumerGroup,
      state = cgl.state().toScala.map(_.name())
    )
  }

  def fromKafkaDescription(cgd: ConsumerGroupDescription): ConsumerGroup = {
    ConsumerGroup(
      groupId = WsGroupId.apply(cgd.groupId()),
      isSimple = cgd.isSimpleConsumerGroup,
      state = Option(cgd.state()).map(_.name()),
      partitionAssignor = Option(cgd.partitionAssignor()),
      members = Option(cgd.members())
        .map(_.asScala)
        .getOrElse(Iterable.empty)
        .toSeq
        .map(m => Option(m.clientId()).map(WsClientId.apply))
        .collect { case Some(clientId) => clientId }
    )
  }

}

case class PartitionOffsetMetadata(
    topic: TopicName,
    partition: Partition,
    offset: Offset,
    metadata: Option[String]
) {

  def asKafkaTopicPartition: TopicPartition = {
    new TopicPartition(topic.value, partition.value)
  }

  private[this] def asKafkaOffsetAndMetadata: OffsetAndMetadata = {
    new OffsetAndMetadata(
      offset.value,
      metadata.orNull
    )
  }

  def asKafkaTuple: (TopicPartition, OffsetAndMetadata) = {
    asKafkaTopicPartition -> asKafkaOffsetAndMetadata
  }
}

object PartitionOffsetMetadata {
  def fromKafkaTuple(
      t: (TopicPartition, OffsetAndMetadata)
  ): PartitionOffsetMetadata = {
    PartitionOffsetMetadata(
      topic = TopicName(t._1.topic()),
      partition = Partition(t._1.partition()),
      offset = Offset(t._2.offset()),
      metadata = t._2.metadata().asOption
    )
  }

  def listToKafkaMap(
      poffs: List[PartitionOffsetMetadata]
  ): Map[TopicPartition, OffsetAndMetadata] = {
    poffs.map(_.asKafkaTuple).toMap
  }
}
