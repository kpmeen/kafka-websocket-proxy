package net.scalytica.kafka.wsproxy.gatling

import io.gatling.core.Predef._
import io.gatling.http.Predef._

import scala.concurrent.duration._

// scalastyle:off magic.number
class BaselineSimulation extends BaseConsumerSimulation {

  val topic      = "test1"
  val payload    = "json"
  val valTpe     = "string"
  val autoCommit = "true"

  val scn = scenario("Baseline WebSocket")
    .exec { session =>
      session.setAll(
        "cid" -> ("gatling-consumer-" + session.userId),
        "gid" -> ("gatling-group-" + session.userId)
      )
    }
    .exec {
      ws("Connect WS")
        .connect(socketOutUri(topic, payload, valTpe))
        .await(30 seconds)(
          multiWsChecks(topic = topic, hasKey = false, num = 1): _*
        )
    }
    .exec(ws("Close WS").close)

  setUp(scn.inject(atOnceUsers(30))).protocols(httpProtocol)
}
// scalastyle:on magic.number
