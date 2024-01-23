package net.scalytica.kafka.wsproxy

import org.apache.pekko.Done
import org.apache.pekko.actor.CoordinatedShutdown
import org.apache.pekko.actor.CoordinatedShutdown._
import org.apache.pekko.actor.typed.scaladsl.adapter._
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.stream.Materializer
import org.apache.pekko.util.Timeout
import net.scalytica.kafka.wsproxy.auth.OpenIdClient
import net.scalytica.kafka.wsproxy.config.Configuration.{
  AppCfg,
  OpenIdConnectCfg
}
import net.scalytica.kafka.wsproxy.config.DynamicConfigHandlerImplicits._
import net.scalytica.kafka.wsproxy.config.{
  Configuration,
  DynamicConfigHandler,
  ReadableDynamicConfigHandlerRef,
  RunnableDynamicConfigHandlerRef
}
import net.scalytica.kafka.wsproxy.jmx.{JmxManager, WsProxyJmxRegistrar}
import net.scalytica.kafka.wsproxy.logging.DefaultProxyLogger._
import net.scalytica.kafka.wsproxy.logging.WsProxyEnvLoggerConfigurator
import net.scalytica.kafka.wsproxy.session.{SessionHandler, SessionHandlerRef}
import net.scalytica.kafka.wsproxy.session.SessionHandlerImplicits._
import net.scalytica.kafka.wsproxy.utils.HostResolver
import net.scalytica.kafka.wsproxy.utils.HostResolver.{
  resolveKafkaBootstrapHosts,
  HostResolutionError
}
import net.scalytica.kafka.wsproxy.web.{ServerBindings, ServerRoutes}
import org.apache.pekko.actor.typed.Scheduler
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.kafka.scaladsl.Consumer
import org.apache.pekko.stream.scaladsl.RunnableGraph

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

  val config = Configuration.loadLightbendConfig()

  implicit val cfg: AppCfg = Configuration.loadConfig(config)

  override val serverId = cfg.server.serverId

  implicit val classicSys: org.apache.pekko.actor.ActorSystem =
    org.apache.pekko.actor.ActorSystem("kafka-ws-proxy", config)

  implicit val mat: Materializer     = Materializer.matFromSystem
  implicit val ctx: ExecutionContext = classicSys.dispatcher

  private val resStatus: HostResolver.HostResolutionStatus =
    resolveKafkaBootstrapHosts(cfg.kafkaClient.bootstrapHosts)

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

  implicit val maybeOpenIdClient: Option[OpenIdClient] =
    cfg.server.openidConnect.collect {
      case oidc: OpenIdConnectCfg if oidc.enabled => OpenIdClient(cfg)
    }

  implicit val jmxManager: Option[JmxManager] = Option(JmxManager())

  implicit val sessionHandler: SessionHandlerRef = SessionHandler.init
  private[this] val sessionHandlerStream: RunnableGraph[Consumer.Control] =
    sessionHandler.stream

  implicit val optRunnableDynCfgHandler
      : Option[RunnableDynamicConfigHandlerRef] = {
    if (cfg.dynamicConfigHandler.enabled) Option(DynamicConfigHandler.init)
    else None
  }
  implicit val optReadableDynCfgHandler
      : Option[ReadableDynamicConfigHandlerRef] =
    optRunnableDynCfgHandler.map(_.asReadOnlyRef)

  private[this] val plainPort = cfg.server.port

  implicit val routes: Route = wsProxyRoutes

  val dynCfgCtrl  = optRunnableDynCfgHandler.map(_.stream.run())
  val sessionCtrl = sessionHandlerStream.run()

  /** Bind to network interface and port, starting the server */
  private[this] val bindingPlain: Option[Future[Http.ServerBinding]] =
    initialisePlainBinding
  private[this] val bindingSecure: Option[Future[Http.ServerBinding]] =
    initialiseSslBinding
  private[this] val bindingAdmin: Option[Future[Http.ServerBinding]] =
    initialiseAdminBinding(adminRoutes)

  private[this] def unbindConnection(
      binding: Future[Http.ServerBinding]
  ): Future[Done] = {
    binding.flatMap(_.terminate(10 seconds)).map(_ => Done)
  }

  private[this] def unbindAll(): Future[Done] = {
    for {
      _ <- bindingPlain.map(unbindConnection).getOrElse(evalDone)
      _ <- bindingSecure.map(unbindConnection).getOrElse(evalDone)
      _ <- bindingAdmin.map(unbindConnection).getOrElse(evalDone)
    } yield Done
  }

  val shutdown: CoordinatedShutdown = {
    val cs = CoordinatedShutdown(classicSys)

    cs.addTask(PhaseServiceUnbind, "http-unbind") { () =>
      info("Gracefully terminating HTTP network bindings...")
      unbindAll()
    }

    cs.addTask(PhaseServiceStop, "http-shutdown-connection-pools") { () =>
      info("Shutting down HTTP connection pools...")
      Http().shutdownAllConnectionPools().map(_ => Done)
    }

    cs.addTask(PhaseBeforeClusterShutdown, "internal-consumer-shutdown") { () =>
      info("Session data consumer shutting down...")
      sessionCtrl.drainAndShutdown(evalDone)
      info("Dynamic config consumer shutting down...")
      dynCfgCtrl.map(_.drainAndShutdown(evalDone)).getOrElse(evalDone)
    }

    cs.addTask(PhaseBeforeActorSystemTerminate, "cleanup-state") { () =>
      implicit val timeout: Timeout          = 10 seconds
      implicit val typedScheduler: Scheduler = classicSys.scheduler.toTyped

      info("Shutting down dynamic config handler...")
      optRunnableDynCfgHandler
        .map { r =>
          r.dynamicConfigHandlerStop().map { _ =>
            info("Dynamic config handler has been stopped.")
            Done
          }
        }
        .getOrElse(evalDone)

      info("Cleaning up internal session state...")
      sessionHandler.shRef.sessionHandlerStop().map { _ =>
        info("Session handler has been stopped.")
        Done
      }
      // Unregister all MBeans properly
      WsProxyJmxRegistrar.unregisterAllWsProxyMBeans()
      // Ensure that the admin client in the JMX Manager is closed.
      Future.successful(
        jmxManager.map(_.close()).map(_ => Done).getOrElse(Done)
      )
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
