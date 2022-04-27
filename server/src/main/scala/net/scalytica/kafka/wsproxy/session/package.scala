package net.scalytica.kafka.wsproxy

import akka.kafka.scaladsl.Consumer
import akka.stream.scaladsl.Source
import net.scalytica.kafka.wsproxy.config.Configuration.AppCfg

package object session {

  type SessionSource = Source[SessionHandlerProtocol.Protocol, Consumer.Control]

  def sessionConsumerGroupId(implicit cfg: AppCfg): String = {
    s"ws-proxy-session-consumer-${cfg.server.serverId.value}"
  }
}
