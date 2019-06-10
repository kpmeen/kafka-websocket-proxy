package net.scalytica.test

import net.scalytica.kafka.wsproxy.{BaseRoutes, SchemaRoutes, ServerRoutes}

trait TestSchemaRoutes  extends SchemaRoutes with BaseRoutes
object TestSchemaRoutes extends TestSchemaRoutes

trait TestServerRoutes  extends ServerRoutes
object TestServerRoutes extends TestServerRoutes
