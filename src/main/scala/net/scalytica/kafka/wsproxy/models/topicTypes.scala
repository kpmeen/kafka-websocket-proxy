package net.scalytica.kafka.wsproxy.models

case class TopicName(value: String) extends AnyVal
case class Partition(value: Int)    extends AnyVal
case class Offset(value: Long)      extends AnyVal
case class Timestamp(value: Long)   extends AnyVal
