package net.scalytica.test

import net.scalytica.kafka.wsproxy.web.{
  BaseRoutes,
  SchemaRoutes,
  ServerRoutes,
  StatusRoutes
}

trait TestSchemaRoutes  extends SchemaRoutes with BaseRoutes
object TestSchemaRoutes extends TestSchemaRoutes

trait TestStatusRoutes  extends StatusRoutes with BaseRoutes
object TestStatusRoutes extends TestStatusRoutes

trait TestServerRoutes  extends ServerRoutes
object TestServerRoutes extends TestServerRoutes
