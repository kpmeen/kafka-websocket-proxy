package net.scalytica.test

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.adapter._
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.server.Directives.handleExceptions
import org.apache.pekko.stream.Materializer
import net.scalytica.kafka.wsproxy.models.{FullClientId, WsServerId}
import net.scalytica.kafka.wsproxy.session.{SessionHandlerProtocol, SessionId}
import net.scalytica.kafka.wsproxy.web._

trait TestAdHocRoute extends RoutesPrereqs with RouteFailureHandlers {
  override val serverId: WsServerId = WsServerId("node-1")

  override def cleanupClient(sid: SessionId, fid: FullClientId)(
      implicit sh: ActorRef[SessionHandlerProtocol.Protocol],
      mat: Materializer
  ): Unit = ()

  def routeWithExceptionHandler(route: Route)(
      implicit as: ActorSystem,
      mat: Materializer
  ): Route = {
    implicit val shRef = as.toTyped.ignoreRef[SessionHandlerProtocol.Protocol]
    handleExceptions(wsExceptionHandler)(route)
  }
}

/** Routes for testing [[SchemaRoutes]] */
trait TestSchemaRoutes extends SchemaRoutes with BaseRoutes {
  override val serverId: WsServerId = WsServerId("node-1")
}

/** Routes for testing [[StatusRoutes]] */
trait TestStatusRoutes extends StatusRoutes with BaseRoutes {
  override val serverId: WsServerId = WsServerId("node-1")
}

/** Routes for testing [[AdminRoutes]] */
trait TestAdminRoutes extends AdminRoutes with BaseRoutes {
  override val serverId: WsServerId = WsServerId("node-1")
}

/** Routes for testing [[ServerRoutes]] */
trait TestServerRoutes extends ServerRoutes {
  override val serverId: WsServerId = WsServerId("node-1")
}
