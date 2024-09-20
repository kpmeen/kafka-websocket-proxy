package net.scalytica.kafka.wsproxy

import net.scalytica.kafka.wsproxy.config.Configuration.AppCfg
import net.scalytica.kafka.wsproxy.session.SessionHandlerProtocol._

import org.apache.pekko.kafka.scaladsl.Consumer.Control
import org.apache.pekko.stream.scaladsl.Source
// scalastyle:on line.size.limit

package object session {

  type SessionSource = Source[SessionProtocol, Control]

  val SessionConsumerGroupIdPrefix: String = "ws-proxy-session-consumer"

  private[session] def sessionConsumerGroupId(implicit cfg: AppCfg): String = {
    s"$SessionConsumerGroupIdPrefix-${cfg.server.serverId.value}"
  }
}
