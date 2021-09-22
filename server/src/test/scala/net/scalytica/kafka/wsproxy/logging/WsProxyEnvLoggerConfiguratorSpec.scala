package net.scalytica.kafka.wsproxy.logging

import akka.actor.ActorSystem
import akka.stream.Materializer
import ch.qos.logback.classic.{Logger => LogbackLogger, LoggerContext}
import com.typesafe.config.ConfigFactory
import net.scalytica.test.FileLoader
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{Assertion, BeforeAndAfterAll, OptionValues}
import org.slf4j.LoggerFactory

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

// INFO: This class is prefixed with capital A to enforce being executed first
class WsProxyEnvLoggerConfiguratorSpec
    extends AnyWordSpec
    with Matchers
    with OptionValues
    with BeforeAndAfterAll {

  implicit private val as =
    ActorSystem.create("log-test-sys", ConfigFactory.empty())
  implicit private val mat = Materializer(as)

  private val rootLogger         = "ROOT"
  private val scalyticaLogger    = "net.scalytica"
  private val proxyLogger        = "net.scalytica.kafka.wsproxy"
  private val akkaKafkaLogger    = "akka.kafka"
  private val akkaActorLogger    = "akka.actor"
  private val kafkaClientsLogger = "org.apache.kafka.clients"
  private val testLogger         = "test.TestLogger"
  private val logbackConfigEnv   = WsProxyEnvLoggerConfigurator.LogbackCfgEnv
  private val loggerEnvPrefix    = WsProxyEnvLoggerConfigurator.PropPrefix

  private val testEnvLogger = s"$loggerEnvPrefix$testLogger"

  private val logbackConfigString =
    """<configuration>
      |
      |  <contextListener class="ch.qos.logback.classic.jul.LevelChangePropagator">
      |    <resetJUL>true</resetJUL>
      |  </contextListener>
      |
      |  <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
      |    <encoder>
      |      <pattern>
      |        %date{HH:mm:ss.SSS} %highlight(%-15level) %cyan(%logger{55}) - %msg%n
      |      </pattern>
      |    </encoder>
      |  </appender>
      |
      |  <logger name="akka.actor" level="WARN"/>
      |  <logger name="akka.kafka" level="WARN"/>
      |  <logger name="net.scalytica.kafka.wsproxy" level="DEBUG"/>
      |
      |  <!-- Root loggers catch all other events that are not explicitly handled-->
      |  <root level="ERROR">
      |    <appender-ref ref="STDOUT"/>
      |  </root>
      |
      |</configuration>
      |""".stripMargin

  val defaultLoggers = Map[String, Option[String]](
    "ROOT"                     -> Some("OFF"),
    "akka.actor"               -> Some("OFF"),
    "akka.kafka"               -> Some("OFF"),
    "net.scalytica"            -> Some("OFF"),
    "org.apache.kafka.clients" -> Some("OFF")
  )

  override def afterAll(): Unit = {
    // Not sure why the system properties aren't properly cleared when
    // removing a property key. Every test below still has property values
    // from preceding tests.
    sys.props -= testEnvLogger
    sys.props -= s"$loggerEnvPrefix$scalyticaLogger"
    sys.props -= logbackConfigEnv

    mat.shutdown()
    val _ = Await.result(as.terminate(), 2 seconds)
  }

  private def testContext(
      testCase: Int,
      props: (String, String)*
  )(
      body: (LoggerContext, Map[String, Option[String]]) => Assertion
  ): Unit = {
    props.foreach(p => sys.props += p._1 -> p._2)

    WsProxyEnvLoggerConfigurator.reload()

    val logger     = LoggerFactory.getLogger(rootLogger)
    val logCtx     = logger.asInstanceOf[LogbackLogger].getLoggerContext
    val configured = logCtx.getLoggerList.asScala

    val configuredLevels = configured.map { l =>
      l.getName -> Option(l.getLevel).map(_.toString)
    }.toMap

    body(logCtx, configuredLevels)

    WsProxyEnvLoggerConfigurator.reset()
    WsProxyEnvLoggerConfigurator.loadConfigString(
      FileLoader.logbackConfigForTestcase(testCase)
    )
  }

  "The WsProxyEnvLoggerConfigurator" should {

    // IMPORTANT: Do NOT change the order of the below tests.

    "not modify loggers when no logger configs are defined in the env" in
      testContext(1) { (_, levels) =>
        levels.keys must not contain testLogger
        levels must contain allElementsOf defaultLoggers
      }

    "set level for logger from environment" in
      testContext(1, testEnvLogger -> "DEBUG") { (_, levels) =>
        levels must contain(testLogger -> Some("DEBUG"))
        levels must contain allElementsOf defaultLoggers
      }

    "udpate level for pre-configured logger" in
      testContext(1, s"$loggerEnvPrefix$scalyticaLogger" -> "DEBUG") {
        (_, levels) =>
          levels must contain(scalyticaLogger -> Some("DEBUG"))
          levels must contain(rootLogger -> Some("OFF"))
          levels must contain(akkaActorLogger -> Some("OFF"))
          levels must contain(akkaKafkaLogger -> Some("OFF"))
          levels must contain(kafkaClientsLogger -> Some("OFF"))
      }

    "set the configuration from an environment string value" in
      testContext(1, logbackConfigEnv -> logbackConfigString) { (_, levels) =>
        // assert keys with value null
        levels.get(scalyticaLogger).value mustBe None
        levels.get("akka").value mustBe None
        levels.get("net").value mustBe None
        levels.get("org").value mustBe None
        levels.get("org.apache").value mustBe None
        // assert values
        levels.get(rootLogger).value mustBe Some("ERROR")
        levels.get(akkaActorLogger).value mustBe Some("WARN")
        levels.get(akkaKafkaLogger).value mustBe Some("WARN")
        levels.get(proxyLogger).value mustBe Some("DEBUG")
        // assert non-existing keys
        levels.get("io.confluent") mustBe None
      }

  }

}
