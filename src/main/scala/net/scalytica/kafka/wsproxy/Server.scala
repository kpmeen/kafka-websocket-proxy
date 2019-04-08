package net.scalytica.kafka.wsproxy

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import net.scalytica.kafka.wsproxy.Configuration.AppCfg

import scala.concurrent.ExecutionContext
import scala.io.StdIn

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

  implicit val cfg: AppCfg            = Configuration.load()
  implicit val sys: ActorSystem       = ActorSystem()
  implicit val mat: ActorMaterializer = ActorMaterializer()
  implicit val ctx: ExecutionContext  = sys.dispatcher

  private[this] val port = cfg.server.port

  /** Bind to network interface and port, starting the server */
  private[this] val bindingFuture = Http().bindAndHandle(
    handler = routes,
    interface = "localhost",
    port = port
  )

  // scalastyle:off
  println(
    s"""Server online at http://localhost:$port/
       |Press RETURN to stop...""".stripMargin
  )
  StdIn.readLine()
  // scalastyle:on

  /** Unbind from the network interface and port, shutting down the server. */
  bindingFuture.flatMap(_.unbind()).onComplete(_ => sys.terminate())
}
