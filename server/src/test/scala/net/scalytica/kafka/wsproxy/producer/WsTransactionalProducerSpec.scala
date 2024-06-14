package net.scalytica.kafka.wsproxy.producer

import org.apache.pekko.NotUsed
import org.apache.pekko.actor.{typed, ActorSystem}
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.kafka.{ProducerMessage, ProducerSettings}
import org.apache.pekko.stream.{KillSwitches, Materializer}
import org.apache.pekko.stream.scaladsl._
import io.github.embeddedkafka.EmbeddedKafkaConfig
import net.scalytica.kafka.wsproxy.codecs.BasicSerdes.{
  StringDeserializer,
  StringSerializer
}
import net.scalytica.kafka.wsproxy.mapToProperties
import net.scalytica.kafka.wsproxy.models.Formats.StringType
import net.scalytica.kafka.wsproxy.models.ValueDetails.InValueDetails
import net.scalytica.kafka.wsproxy.models.WsProducerRecord.asKafkaProducerRecord
import net.scalytica.kafka.wsproxy.models.WsProducerResult.fromProducerResult
import net.scalytica.kafka.wsproxy.models._
import net.scalytica.kafka.wsproxy.producer.WsTransactionalProducer.flexiFlow
import net.scalytica.test.SharedAttributes.defaultTypesafeConfig
import net.scalytica.test.{
  ExtraAssertions,
  WsProxySpec,
  WsReusableProxyKafkaFixture
}
import org.apache.kafka.clients.producer.ProducerConfig._
import org.apache.kafka.clients.producer.{KafkaProducer, Producer}
import org.apache.kafka.common.errors.ProducerFencedException
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.matchers.must.Matchers
import org.scalatest.time.{Minute, Span}
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{BeforeAndAfterAll, OptionValues}

import scala.concurrent.duration._

// scalastyle:off magic.number
class WsTransactionalProducerSpec
    extends AnyWordSpec
    with WsProxySpec
    with WsReusableProxyKafkaFixture
    with BeforeAndAfterAll
    with Matchers
    with OptionValues
    with Eventually
    with ScalaFutures
    with ExtraAssertions {

  override protected val testTopicPrefix: String =
    "transactional-producer-test-topic"

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(1, Minute))

  private[this] val atk =
    ActorTestKit("ws-transactional-producer-spec", defaultTypesafeConfig)

  implicit private[this] val as: typed.ActorSystem[Nothing] = atk.system
  implicit private[this] val mat: Materializer = Materializer.matFromSystem

  override def afterAll(): Unit = {
    mat.shutdown()
    as.terminate()
    atk.shutdownTestKit()

    super.afterAll()
  }

  private[this] type ProdFactory =
    ProducerSettings[String, String] => Producer[String, String]

  // Helper function to initialise the producer client
  private[this] def producerSettings(
      producerId: WsProducerId
  )(transactionId: Option[String])(
      prodFactory: Option[ProdFactory]
  )(
      implicit as: ActorSystem,
      kcfg: EmbeddedKafkaConfig
  ): ProducerSettings[String, String] = {
    val ps1 = ProducerSettings(as, StringSerializer, StringSerializer)
      .withCloseProducerOnStop(true)
      .withBootstrapServers(s"localhost:${kcfg.kafkaPort}")
      .withProperty(CLIENT_ID_CONFIG, producerId.value)
      .withProperty(ENABLE_IDEMPOTENCE_CONFIG, "true")
      .withProperty(ACKS_CONFIG, "all")
      .withProperty(MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, "1")

    val ps2 = transactionId
      .map(tid => ps1.withProperty(TRANSACTIONAL_ID_CONFIG, tid))
      .getOrElse(ps1)

    prodFactory.map(factory => ps2.withProducerFactory(factory)).getOrElse(ps2)
  }

  private[this] def records(
      transactionId: String,
      topic: TopicName,
      num: Int
  ): Seq[ProducerEnvelope] = (1 to num).map { i =>
    val rec = ProducerKeyValueRecord[String, String](
      InValueDetails[String](s"key-$i", StringType),
      InValueDetails[String](s"value-$i", StringType),
      None,
      Some(transactionId)
    )

    ProducerMessage.single(asKafkaProducerRecord(topic, rec), rec)
  }

  private[this] def createTestFlow(
      settings: ProducerSettings[String, String],
      topicName: TopicName
  ) = {
    flexiFlow[String, String, ProducerRecord](
      settings = settings,
      transactionalId = topicName.value
    ).map(r => fromProducerResult(r)).flatMapConcat(seqToSource)
  }

  private[this] def seqToSource(
      s: Seq[WsProducerResult]
  ): Source[WsProducerResult, NotUsed] = {
    val it = new scala.collection.immutable.Iterable[WsProducerResult] {
      override def iterator: Iterator[WsProducerResult] = s.iterator
    }
    Source(it)
  }

  "The transactional producer flow" should {
    "successfully create a transactional producer flow" in
      withNoContext() { case (_, _) =>
        implicit val kcfg =
          kafkaContext.configWithReadIsolationLevel(ReadCommitted)

        val producerId = WsProducerId("test-producer-1")
        val transId    = "test-producer-1"
        val topic      = TopicName("test-topic-1")
        val settings   = producerSettings(producerId)(Some(transId))(None)
        val msgs       = records(transId, topic, 20)

        kafkaContext.createTopics(Map(topic.value -> 1))

        val result =
          Source(msgs)
            .via(createTestFlow(settings, topic))
            .runWith(Sink.seq)
            .futureValue

        result.size mustBe 20

        val written = consumeNumberKeyedMessagesFrom[String, String](
          topic = topic.value,
          number = msgs.size,
          timeout = 30 seconds
        )
        written must have size 20

        val expected =
          msgs
            .map(m => asKafkaProducerRecord(topic, m.passThrough))
            .map(pr => pr.key() -> pr.value())

        written must contain allElementsOf expected
      }

    "not allow creating multiple producers with the same transactional.id" in
      withNoContext() { case (_, _) =>
        implicit val kcfg =
          kafkaContext.configWithReadIsolationLevel(ReadCommitted)

        val pid1    = WsProducerId("test-producer-2a")
        val pid2    = WsProducerId("test-producer-2b")
        val transId = "test-producer-2"
        val topic   = TopicName("test-topic-2")
        val s1      = producerSettings(pid1)(Some(transId))(None)
        val s2      = producerSettings(pid2)(Some(transId))(None)
        val msgs    = records(transId, topic, 10)

        kafkaContext.createTopics(Map(topic.value -> 1))

        val src = Source(msgs).take(msgs.size.toLong).throttle(1, 200 millis)
        val (killer, flow1) = src
          .viaMat(KillSwitches.single)(Keep.right)
          .via(createTestFlow(s1, topic))
          .toMat(Sink.seq)(Keep.both)
          .run()
        val flow2 = src.via(createTestFlow(s2, topic)).runWith(Sink.seq)

        // The oldest flow should be fenced when a new one is created.
        assertCause[ProducerFencedException] {
          val _ = flow1.futureValue
        }
        killer.shutdown()

        val res = flow2.futureValue
        res.size mustBe msgs.size
      }

    "fail to create the flow when transactional id is not provided" in {
      val settings =
        producerSettings(WsProducerId("test-producer-3"))(None)(None)
      assertThrows[IllegalArgumentException] {
        flexiFlow[String, String, ProducerRecord](
          settings = settings,
          transactionalId = null // scalastyle:ignore
        )
      }

    }

    "fail to create create the flow if the producer factory is shared" in {
      val transId = Option("test-producer-1")
      val settings =
        producerSettings(WsProducerId("test-producer-4"))(transId)(Some { ps =>
          new KafkaProducer[String, String](
            ps.properties,
            ps.keySerializerOpt.orNull,
            ps.valueSerializerOpt.orNull
          )
        })
      assertThrows[IllegalArgumentException] {
        flexiFlow[String, String, ProducerRecord](
          settings = settings,
          transactionalId = transId.value
        )
      }
    }

  }

}
