package net.scalytica.kafka.wsproxy.gatling

import io.gatling.core.Predef._
import io.gatling.core.session.Expression
import io.gatling.http.Predef._
import io.gatling.http.check.ws.WsFrameCheck

trait BaseConsumerSimulation extends Simulation {

  val httpProtocol = http
    .baseUrl("http://localhost:8078")
    .acceptHeader("application/json;q=0.9,*/*;q=0.8")
    .doNotTrackHeader("1")
    .acceptEncodingHeader("gzip, deflate")
    .userAgentHeader("Gatling")
    .wsBaseUrl("ws://localhost:8078")

  def socketOutUri(
      topic: String,
      payload: String,
      valTpe: String,
      keyTpe: Option[String] = None,
      autoCommit: Boolean = true
  ): Expression[String] = {
    val uriStr = "/socket/out?" +
      s"clientId=client-${System.currentTimeMillis}&" +
      s"topic=$topic&" +
      s"socketPayload=$payload&" +
      s"valType=$valTpe&" +
      s"autoCommit=$autoCommit"

    val uri = keyTpe.map(k => s"$uriStr&keyType=$k").getOrElse(uriStr)
    uri
  }

  def wsCheck(topic: String, hasKey: Boolean = false): WsFrameCheck = {
    ws.checkTextMessage("Validate")
      .check(jsonPath("$.wsProxyMessageId").exists)
      .check(jsonPath("$.topic").is(topic))
      .check(jsonPath("$.partition").ofType[Int].exists)
      .check(jsonPath("$.offset").ofType[Long].exists)
      .check(jsonPath("$.timestamp").ofType[Long].exists)
      .check(jsonPath("$.value").exists)
      .check {
        if (hasKey) jsonPath("$.key").exists
        else jsonPath("$.key").notExists
      }
  }

  def multiWsChecks(
      topic: String,
      hasKey: Boolean = false,
      num: Int = 1
  ): Seq[WsFrameCheck] = (0 to num).map(_ => wsCheck(topic, hasKey))

}
