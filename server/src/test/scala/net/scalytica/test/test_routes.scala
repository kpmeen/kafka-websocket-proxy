package net.scalytica.test

import net.scalytica.kafka.wsproxy.models.WsServerId
import net.scalytica.kafka.wsproxy.web.{
  AdminRoutes,
  BaseRoutes,
  SchemaRoutes,
  ServerRoutes,
  StatusRoutes
}

trait TestSchemaRoutes extends SchemaRoutes with BaseRoutes

object TestSchemaRoutes extends TestSchemaRoutes {
  override val serverId = WsServerId("node-1")
}

trait TestStatusRoutes extends StatusRoutes with BaseRoutes

object TestStatusRoutes extends TestStatusRoutes {
  override val serverId = WsServerId("node-1")
}

trait TestServerRoutes extends ServerRoutes

object TestServerRoutes extends TestServerRoutes {
  override val serverId = WsServerId("node-1")
}

trait TestAdminRoutes extends AdminRoutes with BaseRoutes

object TestAdminRoutes extends TestAdminRoutes {
  override val serverId = WsServerId("node-1")
}
