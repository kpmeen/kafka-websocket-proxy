package net.scalytica.kafka.wsproxy

import akka.Done
import akka.actor.CoordinatedShutdown._
import akka.actor.{ActorSystem, CoordinatedShutdown}
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import com.typesafe.scalalogging.Logger
import net.scalytica.kafka.wsproxy.Configuration.AppCfg
import net.scalytica.kafka.wsproxy.utils.HostResolver

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

object Server extends App with ServerRoutes {

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

  private[this] val logger = Logger(getClass)

  val config = Configuration.loadTypesafeConfig()

  implicit val cfg: AppCfg            = Configuration.loadConfig(config)
  implicit val sys: ActorSystem       = ActorSystem("kafka-ws-proxy", config)
  implicit val mat: ActorMaterializer = ActorMaterializer()
  implicit val ctx: ExecutionContext  = sys.dispatcher

  HostResolver.resolveKafkaBootstrapHosts(cfg.kafkaClient.bootstrapUrls) match {
    case Right(addr) =>
      logger.info(s"Host ${addr.getHostName} was correctly resolved")

    case Left(resolutionError) =>
      logger.warn(resolutionError.reason)
      val _ = Await.result(sys.terminate(), 10 seconds)
      System.exit(1)
  }

  private[this] val port = cfg.server.port

  val (sessionHandlerStream, routes) = wsProxyRoutes

  val ctrl = sessionHandlerStream.run()

  /** Bind to network interface and port, starting the server */
  val binding = Http().bindAndHandle(
    handler = routes,
    interface = "localhost",
    port = port
  )

  val shutdown: CoordinatedShutdown = {
    val cs = CoordinatedShutdown(sys)

    cs.addTask(PhaseServiceUnbind, "http-unbind") { () =>
      for {
        _ <- ctrl.drainAndShutdown(
              Future.successful(logger.info("Session data consumer shutdown."))
            )
        _ <- binding.flatMap(_.unbind())
      } yield Done
    }

    cs.addTask(PhaseServiceRequestsDone, "http-graceful-terminate") { () =>
      /** Unbind from network interface and port, shutting down the server. */
      binding.flatMap(_.terminate(10.seconds)).map(_ => Done)
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
  println(s"""Server online at http://localhost:$port/ ...""")
  // scalastyle:on

}
