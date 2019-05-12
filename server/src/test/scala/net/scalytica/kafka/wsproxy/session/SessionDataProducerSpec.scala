package net.scalytica.kafka.wsproxy.session

import akka.actor.testkit.typed.scaladsl.ActorTestKit
import net.manub.embeddedkafka.schemaregistry._
import net.scalytica.kafka.wsproxy.codecs.{BasicSerdes, SessionSerde}
import net.scalytica.kafka.wsproxy.codecs.Implicits._
import net.scalytica.test.WSProxyKafkaSpec
import org.scalatest.{BeforeAndAfterAll, MustMatchers, OptionValues, WordSpec}
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.time.{Minute, Span}

// scalastyle:off magic.number
class SessionDataProducerSpec
    extends WordSpec
    with WSProxyKafkaSpec
    with MustMatchers
    with OptionValues
    with Eventually
    with ScalaFutures
    with EmbeddedKafka
    with BeforeAndAfterAll {

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(1, Minute))

  val config  = defaultTypesafeConfig
  val testCfg = defaultTestAppCfg

  val sessionTopic = testCfg.sessionHandler.sessionStateTopicName

  val atk          = ActorTestKit("session-data-producer-test", config)
  implicit val sys = atk.system

  implicit val keyDes = BasicSerdes.StringDeserializer
  implicit val valDes = new SessionSerde().deserializer()

  override def afterAll(): Unit = {
    atk.shutdownTestKit()
    super.afterAll()
  }

  private[this] def testSession(i: Int): Session =
    Session(s"c$i", consumerLimit = i)

  val session1 = testSession(1)
  val session2 = testSession(2)
  val session3 = testSession(3)
  val session4 = testSession(4)

  "The SessionDataProducer" should {

    "be able to publish a session record to the session state topic" in
      withRunningKafkaOnFoundPort(embeddedKafkaConfig) { implicit kcfg =>
        implicit val cfg =
          appTestConfig(kcfg.kafkaPort, Option(kcfg.schemaRegistryPort))

        val sdp = new SessionDataProducer()
        // Write the session data to Kafka
        sdp.publish(session1)
        // Verify the data can be consumed
        val (key, value) =
          consumeFirstKeyedMessageFrom[String, Session](sessionTopic)

        key mustBe session1.consumerGroupId
        value.consumerGroupId mustBe session1.consumerGroupId
        value.consumerLimit mustBe session1.consumerLimit
        value.consumers mustBe empty

        sdp.close()
      }

    "be able to publish multiple session records to the session state topic" in
      withRunningKafkaOnFoundPort(embeddedKafkaConfig) { implicit kcfg =>
        implicit val cfg =
          appTestConfig(kcfg.kafkaPort, Option(kcfg.schemaRegistryPort))

        val sdp = new SessionDataProducer()

        val expected = List(session1, session2, session3, session4)
        // Write the session data to Kafka
        expected.foreach(s => sdp.publish(s))

        val recs =
          consumeNumberKeyedMessagesFrom[String, Session](sessionTopic, 4)

        val keys   = recs.map(_._1)
        val values = recs.map(_._2)

        keys must contain allElementsOf expected.map(_.consumerGroupId)
        values must contain allElementsOf expected
      }

    "be able to publish removal of a session from the session state topic" in
      withRunningKafkaOnFoundPort(embeddedKafkaConfig) { implicit kcfg =>
        implicit val cfg =
          appTestConfig(kcfg.kafkaPort, Option(kcfg.schemaRegistryPort))

        val sdp = new SessionDataProducer()

        val in = List(session1, session2, session3, session4)

        // Write the session data to Kafka
        in.foreach(s => sdp.publish(s))
        // Remove session2
        sdp.publishRemoval(session2.consumerGroupId)
        // Verify the presence of all expected messages
        val r1 =
          consumeNumberKeyedMessagesFrom[String, Session](sessionTopic, 5)

        r1.map(_._2) must contain allElementsOf in
        Option(r1.lastOption.value._2) mustBe empty
      }
  }

}
