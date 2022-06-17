package net.scalytica.test

import net.scalytica.kafka.wsproxy.models.WsServerId
import net.scalytica.kafka.wsproxy.web.{
  AdminRoutes,
  BaseRoutes,
  SchemaRoutes,
  ServerRoutes,
  StatusRoutes
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
