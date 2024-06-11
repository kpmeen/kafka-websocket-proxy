package net.scalytica.kafka.wsproxy.models

import org.apache.kafka.clients.admin.TopicDescription

case class SimpleTopicDescription(
    name: TopicName,
    numPartitions: Int
)

object SimpleTopicDescription {

  def fromKafkaTopicDescription(
      td: TopicDescription
  ): SimpleTopicDescription = {
    SimpleTopicDescription(
      name = TopicName(td.name()),
      numPartitions = td.partitions().size()
    )
  }

}
