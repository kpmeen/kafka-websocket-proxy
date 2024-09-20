package net.scalytica.kafka.wsproxy.actor

import java.util.concurrent.TimeoutException

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import net.scalytica.kafka.wsproxy._
import net.scalytica.kafka.wsproxy.logging.WithProxyLogger

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Scheduler
import org.apache.pekko.actor.typed.scaladsl.AskPattern._
import org.apache.pekko.util.Timeout

trait ActorWithProtocolExtensions[Proto, Res] extends WithProxyLogger {
  val ref: ActorRef[Proto]

  /**
   * Simple extension to perform an {{{ask}}} against a typed actor.
   *
   * @param f
   *   The function that will send the command to the actor.
   * @param timeout
   *   The [[Timeout]] to use.
   * @param scheduler
   *   The [[Scheduler]] to use
   * @return
   *   Eventually returns a result of type [[Res]].
   */
  def doAsk(f: ActorRef[Res] => Proto)(
      implicit timeout: Timeout,
      scheduler: Scheduler
  ): Future[Res] = ref.ask[Res](r => f(r))

  /**
   * Same as [[doAsk()]], but with a simple recovery implementation that is most
   * commonly used.
   *
   * @param f
   *   The function that will send the command to the actor.
   * @param incomplete
   *   The function to apply in case of a [[TimeoutException]]. Typically will
   *   be an {{IncompleteOp}} instance from the given protocol [[Proto]].
   * @param ec
   *   The [[ExecutionContext]] to use.
   * @param timeout
   *   The [[Timeout]] to use.
   * @param scheduler
   *   The [[Scheduler]] to use
   * @return
   *   Eventually returns a result of type [[Res]].
   */
  def doAskWithStandardRecovery(
      f: ActorRef[Res] => Proto
  )(incomplete: (String, Throwable) => Res)(
      implicit ec: ExecutionContext,
      timeout: Timeout,
      scheduler: Scheduler
  ): Future[Res] = doAsk(f).recoverWith {
    case t: TimeoutException =>
      val msg = s"Timeout calling ${t.operationName}."
      log.debug(msg, t)
      Future.successful(incomplete(msg, t))

    case t: Throwable =>
      log.warn(s"Unhandled error calling ${t.operationName}.", t)
      throw t
  }
}
