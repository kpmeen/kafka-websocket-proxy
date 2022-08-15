package net.scalytica.test

import akka.actor.{ActorSystem => ClassicActorSystem}
import akka.actor.typed.{ActorRef, ActorSystem}
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.Directives.handleExceptions
import akka.stream.Materializer
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
      implicit as: ClassicActorSystem,
      mat: Materializer
  ): Route = {
    implicit val tas   = ActorSystem.wrap(as)
    implicit val shRef = tas.ignoreRef[SessionHandlerProtocol.Protocol]
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
