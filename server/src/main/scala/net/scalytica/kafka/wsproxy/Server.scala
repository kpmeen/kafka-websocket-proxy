package net.scalytica.kafka.wsproxy

import akka.Done
import akka.actor.CoordinatedShutdown
import akka.actor.CoordinatedShutdown._
import akka.actor.typed.scaladsl.adapter._
import akka.http.scaladsl.Http
import akka.stream.Materializer
import akka.util.Timeout
import net.scalytica.kafka.wsproxy.auth.OpenIdClient
import net.scalytica.kafka.wsproxy.config.Configuration
import net.scalytica.kafka.wsproxy.config.Configuration.{
  AppCfg,
  OpenIdConnectCfg
}
import net.scalytica.kafka.wsproxy.jmx.JmxManager
import net.scalytica.kafka.wsproxy.logging.DefaultProxyLogger._
import net.scalytica.kafka.wsproxy.logging.WsProxyEnvLoggerConfigurator
import net.scalytica.kafka.wsproxy.session.SessionHandler
import net.scalytica.kafka.wsproxy.session.SessionHandler._
import net.scalytica.kafka.wsproxy.utils.HostResolver.{
  resolveKafkaBootstrapHosts,
  HostResolutionError
}
import net.scalytica.kafka.wsproxy.web.{ServerBindings, ServerRoutes}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

object Server extends App with ServerRoutes with ServerBindings {

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

  private[this] val evalDone = Future.successful(Done)

  val config = Configuration.loadTypesafeConfig()

  implicit val cfg: AppCfg = Configuration.loadConfig(config)

  override val serverId = cfg.server.serverId

  implicit val classicSys: akka.actor.ActorSystem =
    akka.actor.ActorSystem("kafka-ws-proxy", config)
  implicit val mat: Materializer     = Materializer.matFromSystem
  implicit val ctx: ExecutionContext = classicSys.dispatcher

  val resStatus = resolveKafkaBootstrapHosts(cfg.kafkaClient.bootstrapHosts)

  if (!resStatus.hasErrors) {
    info("All Kafka broker hosts were correctly resolved")
  } else {
    val reasons = resStatus.results.collect {
      case HostResolutionError(reason) => reason
    }
    warn(
      "Some hosts could not be resolved. Reasons:" +
        s"\n${reasons.mkString("  - ", "\n", "")}"
    )
    val _ = Await.result(classicSys.terminate(), 10 seconds)
    System.exit(1)
  }

  implicit val maybeOpenIdClient = cfg.server.openidConnect.collect {
    case oidc: OpenIdConnectCfg if oidc.enabled => OpenIdClient(cfg)
  }

  implicit val jmxMngr = Option(JmxManager())

  implicit val sessionHandlerRef = SessionHandler.init

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
      info("Gracefully terminating HTTP network bindings...")
      for {
        _ <- bindingPlain.map(unbindConnection).getOrElse(evalDone)
        _ <- bindingSecure.map(unbindConnection).getOrElse(evalDone)
      } yield Done
    }

    cs.addTask(PhaseServiceStop, "http-shutdown-connection-pools") { () =>
      info("Shutting down HTTP connection pools...")
      Http().shutdownAllConnectionPools().map(_ => Done)
    }

    cs.addTask(PhaseBeforeClusterShutdown, "session-cleanup-state") { () =>
      implicit val timeout: Timeout = 10 seconds
      implicit val typedScheduler   = classicSys.scheduler.toTyped
      info("Cleaning up session state...")
      sessionHandlerRef.shRef.sessionHandlerShutdown().map { _ =>
        logger.info("Session handler has been stopped.")
        Done
      }
    }

    cs.addTask(PhaseBeforeActorSystemTerminate, "session-consumer-shutdown") {
      () =>
        info("Session data consumer shutting down...")
        ctrl.drainAndShutdown(evalDone)
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
