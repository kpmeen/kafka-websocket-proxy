/*
    IMPORTANT: The package _must_ be akka.kafka.scaladsl in order to be able to
    use the package protected implementation of TransactionalProducerStage.
 */
package net.scalytica.kafka.wsproxy.producer

import akka.NotUsed
import akka.kafka.ProducerMessage.{Envelope, Results}
import akka.kafka.ProducerSettings
import akka.stream.ActorAttributes
import akka.stream.scaladsl.Flow

object WsTransactionalProducer {

  /**
   * This is a generic variation of [[akka.kafka.scaladsl.Transactional.flow]].
   * Instead of "forcing" the pass-through message to be an instance of
   * [[akka.kafka.ConsumerMessage.PartitionOffset]], it allows sending any data
   * type as the pass through element.
   *
   * @param settings
   *   The Kafka [[ProducerSettings]] to use
   * @param transactionalId
   *   The transactional ID to use for the Kafka producer
   * @tparam K
   *   The type of the key element in the producer message
   * @tparam V
   *   The type of the value element in the producer message
   * @tparam PassThrough
   *   The type of the pass-through element of the result.
   * @return
   *
   * @see
   *   [[akka.kafka.scaladsl.Transactional.flow]]
   */
  def flexiFlow[K, V, PassThrough](
      settings: ProducerSettings[K, V],
      transactionalId: String
  ): Flow[Envelope[K, V, PassThrough], Results[K, V, PassThrough], NotUsed] = {
    require(
      transactionalId != null && transactionalId.nonEmpty,
      "You must define a Transactional id."
    )
    require(
      settings.producerFactorySync.isEmpty,
      "You cannot use a shared or external producer factory."
    )

    val flow = Flow.fromGraph(
      new WsTransactionalProducerStage[K, V, PassThrough](settings)
    )

    if (settings.dispatcher.isEmpty) flow
    else flow.withAttributes(ActorAttributes.dispatcher(settings.dispatcher))
  }

}
