package net.scalytica.kafka.wsproxy

import akka.kafka.scaladsl.Consumer
import akka.stream.scaladsl.Source

package object session {

  type SessionSource = Source[SessionHandlerProtocol.Protocol, Consumer.Control]

}
