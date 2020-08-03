package net.scalytica.kafka.wsproxy.logging

import akka.actor.ActorSystem
import akka.stream.Materializer
import ch.qos.logback.classic.{Logger => LogbackLogger}
import com.typesafe.config.ConfigFactory
import net.scalytica.test.FileLoader
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{Assertion, BeforeAndAfterAll, OptionValues}
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.concurrent.Await
import scala.concurrent.duration._

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

  private val testEnvLogger =
    s"$loggerEnvPrefix$testLogger"

  private val testLogbackConfigFileString = FileLoader.testLogbackConfig

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
    // TODO: Not sure why the system properties aren't properly cleared when
    //       removing a property key. Every test below still has property values
    //       from preceding tests.
    sys.props -= testEnvLogger
    sys.props -= s"$loggerEnvPrefix$scalyticaLogger"
    sys.props -= logbackConfigEnv

    mat.shutdown()
    val _ = Await.result(as.terminate(), 2 seconds)
  }

  private def testContext(
      props: (String, String)*
  )(
      body: Map[String, Option[String]] => Assertion
  ): Unit = {
    props.foreach(p => sys.props += p._1 -> p._2)

    WsProxyEnvLoggerConfigurator.reload()

    val logger     = LoggerFactory.getLogger(rootLogger)
    val logCtx     = logger.asInstanceOf[LogbackLogger].getLoggerContext
    val configured = logCtx.getLoggerList.asScala

    val configuredLevels = configured.map { l =>
      l.getName -> Option(l.getLevel).map(_.toString)
    }.toMap

    body(configuredLevels)

    WsProxyEnvLoggerConfigurator.reset()
    WsProxyEnvLoggerConfigurator.loadConfigString(testLogbackConfigFileString)
  }

  "The WsProxyEnvLoggerConfigurator" should {

    // IMPORTANT: Do NOT change the order of the below tests.

    "not modify loggers if there are no logger configs defined " +
      "in the env" in testContext() { configuredLevels =>
      configuredLevels.keys must not contain testLogger
      configuredLevels must contain allElementsOf defaultLoggers
    }

    "set level for logger from environment" in
      testContext(testEnvLogger -> "DEBUG") { configuredLevels =>
        configuredLevels must contain(testLogger -> Some("DEBUG"))
        configuredLevels must contain allElementsOf defaultLoggers
      }

    "udpate level for pre-configured logger" in
      testContext(s"$loggerEnvPrefix$scalyticaLogger" -> "DEBUG") {
        configuredLevels =>
          configuredLevels must contain(scalyticaLogger -> Some("DEBUG"))
          configuredLevels must contain(rootLogger -> Some("OFF"))
          configuredLevels must contain(akkaActorLogger -> Some("OFF"))
          configuredLevels must contain(akkaKafkaLogger -> Some("OFF"))
          configuredLevels must contain(kafkaClientsLogger -> Some("OFF"))
      }

    "set the configuration from an environment string value" in
      testContext(logbackConfigEnv -> logbackConfigString) { configuredLevels =>
        // assert keys with value null
        configuredLevels.get(scalyticaLogger).value mustBe None
        configuredLevels.get("akka").value mustBe None
        configuredLevels.get("net").value mustBe None
        configuredLevels.get("org").value mustBe None
        configuredLevels.get("org.apache").value mustBe None
        // assert values
        configuredLevels.get(rootLogger).value mustBe Some("ERROR")
        configuredLevels.get(akkaActorLogger).value mustBe Some("WARN")
        configuredLevels.get(akkaKafkaLogger).value mustBe Some("WARN")
        configuredLevels.get(proxyLogger).value mustBe Some("DEBUG")
        // assert non-existing keys
        configuredLevels.get("io.confluent") mustBe None
      }

  }

}
