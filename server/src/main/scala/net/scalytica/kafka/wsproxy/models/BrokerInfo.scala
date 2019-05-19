package net.scalytica.kafka.wsproxy.models

case class BrokerInfo(
    id: Int,
    host: String,
    port: Int,
    rack: Option[String]
)
