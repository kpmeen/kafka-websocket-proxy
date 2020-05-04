package net.scalytica.kafka.wsproxy

import akka.Done
import akka.actor.CoordinatedShutdown
import akka.actor.CoordinatedShutdown._
import akka.http.scaladsl.Http
import akka.stream.Materializer
import net.scalytica.kafka.wsproxy.Configuration.AppCfg
import net.scalytica.kafka.wsproxy.logging.{
  WithProxyLogger,
  WsProxyEnvLoggerConfigurator
}
import net.scalytica.kafka.wsproxy.utils.HostResolver.{
  resolveKafkaBootstrapHosts,
  HostResolutionError
}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

object Server
    extends App
    with ServerRoutes
    with ServerBindings
    with WithProxyLogger {

  // scalastyle:off
  println(
    """
      |               _   __         __  _
      |              | | / /        / _|| |
      |              | |/ /   __ _ | |_ | | __ __ _
      |              |    \  / _` ||  _|| |/ // _` |
      |              | |\  \| (_| || |  |   <| (_| |
      |              \_| \_/ \__,_||_|_____\_\\__,_|
      | _    _        _      _____               _          _
      || |  | |      | |    /  ___|             | |        | |
      || |  | |  ___ | |__  \ `--.   ___    ___ | | __ ___ | |_
      || |/\| | / _ \| '_ \  `--. \ / _ \  / __|| |/ // _ \| __|
      |\  /\  /|  __/| |_) |/\__/ /| (_) || (__ |   <|  __/| |_
      | \/  \/  \___||_.__/ \____/  \___/  \___||_|\_\\___| \__|
      |               _____
      |              | ___ \
      |              | |_/ /_ __  ___ __  __ _   _
      |              |  __/| '__|/ _ \\ \/ /| | | |
      |              | |   | |  | (_) |>  < | |_| |
      |              \_|   |_|   \___//_/\_\ \__, |
      |                                       __/ |
      |                                      |___/
      |
      |""".stripMargin
    // scalastyle:on
  )

  WsProxyEnvLoggerConfigurator.load()

  private[this] val evalOpt  = Future.successful(None)
  private[this] val evalDone = Future.successful(Done)

  val config = Configuration.loadTypesafeConfig()

  implicit val cfg: AppCfg = Configuration.loadConfig(config)

  implicit val classicSys: akka.actor.ActorSystem =
    akka.actor.ActorSystem("kafka-ws-proxy", config)
  implicit val mat: Materializer     = Materializer.matFromSystem
  implicit val ctx: ExecutionContext = classicSys.dispatcher

  val resStatus = resolveKafkaBootstrapHosts(cfg.kafkaClient.bootstrapHosts)
  if (!resStatus.hasErrors) {
    logger.info("All Kafka broker hosts were correctly resolved")
  } else {
    val reasons = resStatus.results.collect {
      case HostResolutionError(reason) => reason
    }
    logger.warn(
      "Some hosts could not be resolved. Reasons:" +
        s"\n${reasons.mkString("  - ", "\n", "")}"
    )
    val _ = Await.result(classicSys.terminate(), 10 seconds)
    System.exit(1)
  }

  private[this] val plainPort = cfg.server.port

  implicit val (sessionHandlerStream, routes) = wsProxyRoutes

  val ctrl = sessionHandlerStream.run()

  private[this] def unbindConnection(
      binding: Future[Http.ServerBinding]
  ): Future[Done] = {
    binding.flatMap(_.terminate(10 seconds)).map(_ => Done)
  }

  /** Bind to network interface and port, starting the server */
  val bindingPlain  = initialisePlainBinding
  val bindingSecure = initialiseSslBinding

  val shutdown: CoordinatedShutdown = {
    val cs = CoordinatedShutdown(classicSys)

    cs.addTask(PhaseServiceUnbind, "http-unbind") { () =>
      for {
        _ <- ctrl.drainAndShutdown(
              Future.successful(logger.info("Session data consumer shutdown."))
            )
        _ <- bindingPlain.map(_.flatMap(_.unbind())).getOrElse(evalOpt)
        _ <- bindingSecure.map(_.flatMap(_.unbind())).getOrElse(evalOpt)
      } yield Done
    }

    cs.addTask(PhaseServiceRequestsDone, "http-graceful-terminate") { () =>
      /** Unbind from network interface and port, shutting down the server. */
      bindingPlain.map(unbindConnection).getOrElse(evalDone)
      bindingSecure.map(unbindConnection).getOrElse(evalDone)
    }

    cs.addTask(PhaseServiceStop, "http-shutdown") { () =>
      Http().shutdownAllConnectionPools().map(_ => Done)
    }

    cs
  }

  scala.sys.addShutdownHook {
    val _ = shutdown.run(JvmExitReason)
  }

  // scalastyle:off
  println(s"""Server online at http://localhost:$plainPort/ ...""")
  // scalastyle:on

}
