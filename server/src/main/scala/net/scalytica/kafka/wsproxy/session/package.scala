package net.scalytica.kafka.wsproxy

import org.apache.pekko.kafka.scaladsl.Consumer.Control
import org.apache.pekko.stream.scaladsl.Source
import net.scalytica.kafka.wsproxy.config.Configuration.AppCfg
// scalastyle:off line.size.limit
import net.scalytica.kafka.wsproxy.session.SessionHandlerProtocol.SessionProtocol
// scalastyle:on line.size.limit

package object session {

  type SessionSource = Source[SessionProtocol, Control]

  val SessionConsumerGroupIdPrefix: String = "ws-proxy-session-consumer"

  private[session] def sessionConsumerGroupId(implicit cfg: AppCfg): String = {
    s"$SessionConsumerGroupIdPrefix-${cfg.server.serverId.value}"
  }
}
