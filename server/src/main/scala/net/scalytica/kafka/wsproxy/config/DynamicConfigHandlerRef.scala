package net.scalytica.kafka.wsproxy.config

import org.apache.pekko.actor.typed.{ActorRef, ActorSystem}
import org.apache.pekko.kafka.scaladsl.Consumer
import org.apache.pekko.stream.scaladsl.RunnableGraph

/**
 * Parent trait to provide access to the [[ActorRef]] referencing the
 * [[DynamicConfigHandler]] actor.
 */
trait DynamicConfigHandlerRef {
  val dchRef: ActorRef[DynamicConfigHandlerProtocol.DynamicConfigProtocol]
}

/**
 * Keeps a reference to the [[DynamicConfigHandler]] actor, and the Kafka
 * consumer stream that feeds its state changes from Kafka.
 *
 * @param stream
 *   The runnable Kafka Consumer stream used by the [[DynamicConfigHandler]].
 * @param dchRef
 *   The [[ActorRef]] referencing the [[DynamicConfigHandler]] actor.
 */
final case class RunnableDynamicConfigHandlerRef(
    stream: RunnableGraph[Consumer.Control],
    dchRef: ActorRef[DynamicConfigHandlerProtocol.DynamicConfigProtocol]
) extends DynamicConfigHandlerRef {

  def asReadOnlyRef: ReadableDynamicConfigHandlerRef =
    ReadableDynamicConfigHandlerRef(dchRef)

  def stop()(implicit system: ActorSystem[_]): Unit = {
    dchRef.tell(
      DynamicConfigHandlerProtocol.StopConfigHandler(system.ignoreRef)
    )
  }

}

/**
 * Keeps a read-only reference to the [[DynamicConfigHandler]] actor. This is to
 * be used by e.g. the standard endpoints when they need to find configs for
 * connecting clients.
 *
 * @param dchRef
 *   The [[ActorRef]] referencing the [[DynamicConfigHandler]] actor.
 */
final case class ReadableDynamicConfigHandlerRef(
    dchRef: ActorRef[DynamicConfigHandlerProtocol.DynamicConfigProtocol]
) extends DynamicConfigHandlerRef {

  def stop()(implicit system: ActorSystem[_]): Unit = {
    dchRef.tell(
      DynamicConfigHandlerProtocol.StopConfigHandler(system.ignoreRef)
    )
  }
}
