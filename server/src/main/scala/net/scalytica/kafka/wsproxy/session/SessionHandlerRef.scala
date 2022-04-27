package net.scalytica.kafka.wsproxy.session

import akka.actor.typed.ActorRef
import akka.kafka.scaladsl.Consumer
import akka.stream.scaladsl.RunnableGraph

case class SessionHandlerRef(
    stream: RunnableGraph[Consumer.Control],
    shRef: ActorRef[SessionHandlerProtocol.Protocol]
)
