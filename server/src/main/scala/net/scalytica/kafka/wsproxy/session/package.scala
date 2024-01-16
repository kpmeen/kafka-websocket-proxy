package net.scalytica.kafka.wsproxy

import org.apache.pekko.kafka.scaladsl.Consumer
import org.apache.pekko.stream.scaladsl.Source
import net.scalytica.kafka.wsproxy.config.Configuration.AppCfg

package object session {

  type SessionSource = Source[SessionHandlerProtocol.Protocol, Consumer.Control]

  private[session] def sessionConsumerGroupId(implicit cfg: AppCfg): String = {
    s"ws-proxy-session-consumer-${cfg.server.serverId.value}"
  }
}
