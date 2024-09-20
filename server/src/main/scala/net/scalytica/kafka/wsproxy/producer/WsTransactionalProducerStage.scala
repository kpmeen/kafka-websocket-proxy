package net.scalytica.kafka.wsproxy.producer

import scala.annotation.unused
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.util.control.NonFatal

import net.scalytica.kafka.wsproxy.utils.JavaDurationConverters._

import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerConfig._
import org.apache.pekko.Done
import org.apache.pekko.kafka.ProducerMessage._
import org.apache.pekko.kafka.ProducerSettings
import org.apache.pekko.stream._
import org.apache.pekko.stream.stage._

final private[producer] class WsTransactionalProducerStage[K, V, P](
    val settings: ProducerSettings[K, V]
) extends GraphStage[FlowShape[Envelope[K, V, P], Results[K, V, P]]] {

  val in: Inlet[Envelope[K, V, P]]  = Inlet[Envelope[K, V, P]]("messages")
  val out: Outlet[Results[K, V, P]] = Outlet[Results[K, V, P]]("result")
  val shape: FlowShape[Envelope[K, V, P], Results[K, V, P]] = FlowShape(in, out)

  private[this] def requireCheck(expKey: String, expValue: String): Unit = {
    require(
      settings.properties.exists { case (key, value) =>
        key.equals(expKey) && value.equals(expValue)
      },
      s"$expKey must be $expValue when using a transactional producer."
    )
  }

  require(
    settings.properties.exists { case (key, value) =>
      key.equals(TRANSACTIONAL_ID_CONFIG) && value.nonEmpty
    },
    s"$TRANSACTIONAL_ID_CONFIG must be set when using a transactional producer."
  )
  requireCheck(ENABLE_IDEMPOTENCE_CONFIG, "true")
  requireCheck(ACKS_CONFIG, "all")
  requireCheck(MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, "1")

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new WsTransactionalProducerStageLogic(
      this,
      inheritedAttributes
    )
}

private class WsTransactionalProducerStageLogic[K, V, P](
    stage: WsTransactionalProducerStage[K, V, P],
    @unused inheritedAttributes: Attributes
) extends GraphStageLogic(stage.shape)
    with StageLogging {

  private var completionState: Option[Try[Done]] = None

  override protected def logSource: Class[_] =
    classOf[WsTransactionalProducerStageLogic[_, _, _]]

  private val producerSettings: ProducerSettings[K, V] = stage.settings

  private lazy val transactionalId =
    producerSettings.getProperty(TRANSACTIONAL_ID_CONFIG)

  private val producer: Producer[K, V] = producerSettings.createKafkaProducer()

  producer.initTransactions()

  inHandler()
  outHandler()

  private class WsDefaultInHandler extends InHandler {
    override def onPush(): Unit = produce(grab(stage.in))

    override def onUpstreamFinish(): Unit = {
      completionState = Some(Success(Done))
      checkForCompletion()
    }

    override def onUpstreamFailure(ex: Throwable): Unit = {
      completionState = Some(Failure(ex))
      checkForCompletion()
    }
  }

  private def checkForCompletion(): Unit =
    if (isClosed(stage.in)) {
      completionState match {
        case Some(Success(_))  => completeStage()
        case Some(Failure(ex)) => closeAndFailStage(ex)
        case None =>
          failStage(
            new IllegalStateException(
              "Stage completed, but there is no info about status"
            )
          )
      }
    }

  private def closeAndFailStage(ex: Throwable): Unit = {
    abortTransaction(ex.getMessage)
    closeProducerImmediatelyAndFail(ex)
  }

  private def outHandler(): Unit = {
    setHandler(out = stage.out, handler = () => tryPull(stage.in))
  }

  private def inHandler(): Unit =
    setHandler(in = stage.in, handler = new WsDefaultInHandler())

  protected def produce(in: Envelope[K, V, P]): Unit = {
    in match {
      case msg: Message[K, V, P] =>
        produceInTransaction {
          val md = producer.send(msg.record).get()
          push(stage.out, Result(md, msg))
        }

      case multiMsg: MultiMessage[K, V, P] =>
        produceInTransaction {
          val parts = for {
            msg <- multiMsg.records
          } yield {
            val md = producer.send(msg).get()
            MultiResultPart(md, msg)
          }
          push(stage.out, MultiResult(parts, multiMsg.passThrough))
        }

      case _: PassThroughMessage[K, V, P] =>
        push(stage.out, PassThroughResult[K, V, P](in.passThrough))

    }
  }

  override def postStop(): Unit = {
    log.debug("ProducerStage postStop")
    closeProducer()
    super.postStop()
  }

  private def abortTransaction(cause: String): Unit = {
    log.debug("Aborting transaction: {}", cause)
    try {
      producer.abortTransaction()
    } catch {
      case _: Throwable => // ignore
    }
  }

  private[this] lazy val abortMsgTemplate =
    "Aborting because of an error when processing transaction for {} {}. {}."

  private def produceInTransaction(produce: => Unit): Unit = {
    try {
      log.debug("Beginning new transaction")
      producer.beginTransaction()
      produce
      log.debug("Committing transaction")
      producer.commitTransaction()
    } catch {
      case ex: Throwable =>
        log.warning(
          template = abortMsgTemplate,
          arg1 = TRANSACTIONAL_ID_CONFIG,
          arg2 = transactionalId,
          arg3 = ex.getMessage
        )
        closeAndFailStage(ex)
    }
  }

  private def closeProducerImmediatelyAndFail(ex: Throwable): Unit = {
    if (producer != null && producerSettings.closeProducerOnStop) {
      // Discard unsent ProducerRecords after encountering a send-failure in
      // ProducerStage https://github.com/akka/alpakka-kafka/pull/318
      log.debug("Closing the instantiated producer immediately.")
      producer.close(java.time.Duration.ZERO)
    }
    failStage(ex)
  }

  private def closeProducer(): Unit =
    if (producerSettings.closeProducerOnStop) {
      try {
        // we do not have to check if producer was already closed in
        // send-callback as `flush()` and `close()` are effectively no-ops in
        // this case
        producer.flush()
        producer.close(producerSettings.closeTimeout.asJava)
        log.debug("Producer closed")
      } catch {
        case NonFatal(ex) =>
          log.error(ex, "Problem occurred during producer close")
      }
    }
}
