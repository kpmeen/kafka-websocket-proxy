package net.scalytica.kafka.wsproxy.error

object Errors {

  sealed trait WsProxyError {
    val message: String
    val code: Int
  }

  sealed trait WsClientError         extends WsProxyError
  sealed trait WsConstraintViolation extends WsProxyError
  sealed trait WsServerError         extends WsProxyError

  case class TopicNotFound() extends WsClientError {
    override val message: String = "Topic not found."
    override val code: Int       = 40401
  }

  case class PartitionNotFound() extends WsClientError {
    override val message: String = "Partition not found."
    override val code: Int       = 40402
  }
  case class ConsumerInstanceNotFound() extends WsClientError {
    override val message: String = "Consumer instance not found."
    override val code: Int       = 40403
  }

  case class LeaderNotAvailable() extends WsClientError {
    override val message: String = "Leader not available."
    override val code: Int       = 40404
  }

  case class ConsumerFormatMismatch() extends WsClientError {
    override val message: String =
      "The requested embedded data format does not match the deserializer for" +
        " this consumer instance."
    override val code: Int = 40601
  }

  case class ConsumerAlreadySubscribed() extends WsClientError {
    override val message: String =
      "Consumer cannot subscribe the the specified target because it has " +
        "already subscribed to other topics."
    override val code: Int = 40901
  }

  case class ConsumerAlreadyExists() extends WsClientError {
    override val message: String =
      "Consumer with specified consumer ID already exists in the specified " +
        "consumer group."
    override val code: Int = 40902
  }

  case class IllegalState() extends WsClientError {
    override val message: String = "Illegal state"
    override val code: Int       = 40903
  }

  case class KeySchemaMissing() extends WsConstraintViolation {
    override val message: String =
      "Request includes keys but does not include key schema"
    override val code: Int = 42201
  }

  case class ValueSchemaMissing() extends WsConstraintViolation {
    override val message: String =
      "Request includes keys but does not include value schema"
    override val code: Int = 42202
  }

  case class JsonAvroConversionError() extends WsConstraintViolation {
    override val message: String = "Conversion of JSON to Avro failed"
    override val code: Int       = 42203
  }

  case class InvalidConsumerConfig() extends WsConstraintViolation {
    override val message: String = "Invalid consumer configuration"
    override val code: Int       = 42204
  }

  case class InvalidConsumerConfigConstraint() extends WsConstraintViolation {
    override val message: String =
      "Invalid consumer configuration. It does not abide by the constraints"
    override val code: Int = 40001
  }

  case class InvalidSchema() extends WsConstraintViolation {
    override val message: String = "Invalid schema"
    override val code: Int       = 42205
  }

  case class ZooKeeperError() extends WsServerError {
    override val message: String = "ZooKeeper error"
    override val code: Int       = 50001
  }

  case class KafkaError() extends WsServerError {
    override val message: String = "Kafka error"
    override val code: Int       = 50002
  }

  case class KafkaTransientError() extends WsServerError {
    override val message: String = "Transient Kafka error"
    override val code: Int       = 50003
  }

  case class NoSslSupport() extends WsServerError {
    override val message: String =
      "Only SSL endpoints were found for the broker, but SSL is not yet " +
        "supported by the websocket proxy."
    override val code: Int = 50101
  }

  case class NoConsumerAvailable() extends WsServerError {
    override val message: String =
      "No consumer is currently available. To avoid this error in the future," +
        " either retry the request or increase the pool size or timeout."
    override val code: Int = 50301
  }

  case class UnexpectedProducerError() extends WsServerError {
    override val message: String =
      "Unexpected non-Kafka exception returned by Kafka."
    override val code: Int = 50002
  }

}
