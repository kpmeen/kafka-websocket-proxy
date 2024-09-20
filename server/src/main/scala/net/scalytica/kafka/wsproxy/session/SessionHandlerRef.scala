package net.scalytica.kafka.wsproxy.session

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.kafka.scaladsl.Consumer
import org.apache.pekko.stream.scaladsl.RunnableGraph

case class SessionHandlerRef(
    stream: RunnableGraph[Consumer.Control],
    shRef: ActorRef[SessionHandlerProtocol.SessionProtocol]
) {
  def stop()(implicit system: ActorSystem[_]): Unit = {
    shRef.tell(
      SessionHandlerProtocol.StopSessionHandler(system.ignoreRef)
    )
  }
}
