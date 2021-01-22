package net.scalytica.kafka.wsproxy.web

import akka.http.scaladsl.server.Route
import net.scalytica.kafka.wsproxy.models.Formats.AvroType
import net.scalytica.kafka.wsproxy.web.SocketProtocol.AvroPayload
import net.scalytica.test.{
  MockOpenIdServer,
  TestDataGenerators,
//  TestServerRoutes,
  WsProxyConsumerKafkaSpec
}
import org.scalatest.Inspectors.forAll
import org.scalatest.{Assertion, OptionValues}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Minutes, Span}
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.duration._

trait WebSocketRoutesAvroScaffolding
    extends AnyWordSpec
    with OptionValues
    with ScalaFutures
    with WsProxyConsumerKafkaSpec
    with MockOpenIdServer
    with TestDataGenerators {

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(2, Minutes))

  implicit val testKeyDeserializer = TestSerdes.keySerdes.deserializer()
  implicit val albumDeserializer   = TestSerdes.valueSerdes.deserializer()
  implicit val longDeserializer    = TestSerdes.longSerdes.deserializer()

  override protected val testTopicPrefix: String = "avro-test-topic"

  protected def verifyTestKeyAndAlbum(
      testKey: TestKey,
      album: Album,
      expectedIndex: Int = 1
  ): Assertion = {
    testKey.username mustBe s"foo-$expectedIndex"
    album.artist mustBe s"artist-$expectedIndex"
    album.title mustBe s"title-$expectedIndex"
    album.tracks must have size 3
    forAll(album.tracks) { t =>
      t.name must startWith("track-")
      t.duration mustBe (120 seconds).toMillis
    }
  }

  protected def testRequiredQueryParamReject(
      useClientId: Boolean = true,
      useTopicName: Boolean = true,
      useValType: Boolean = true
  )(implicit ctx: ProducerContext): Assertion = {
    implicit val wsClient = ctx.producerProbe

    val cid = producerClientId("avro", topicCounter)

    val messages = createAvroProducerRecordAvroAvro(1)

    val uri = buildProducerUri(
      clientId = if (useClientId) Some(cid) else None,
      topicName = if (useTopicName) Some(ctx.topicName) else None,
      payloadType = Some(AvroPayload),
      keyType = Some(AvroType),
      valType = if (useValType) Some(AvroType) else None
    )

    produceAndCheckAvro(
      clientId = cid,
      topic = ctx.topicName,
      routes = Route.seal(ctx.route),
      keyType = Some(AvroType),
      valType = AvroType,
      messages = messages,
      producerUri = Some(uri)
    )
  }

}
