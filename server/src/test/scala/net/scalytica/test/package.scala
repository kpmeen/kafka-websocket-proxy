package net.scalytica

import org.apache.pekko.http.scaladsl.model.ws.TextMessage
import org.apache.pekko.http.scaladsl.testkit.WSProbe
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Sink
import io.circe.Decoder
import io.circe.parser.parse
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig.{
  AUTO_REGISTER_SCHEMAS,
  SCHEMA_REGISTRY_URL_CONFIG
}
import io.confluent.kafka.serializers.subject.TopicNameStrategy
import io.confluent.kafka.serializers.subject.strategy.SubjectNameStrategy
import io.github.embeddedkafka.{EmbeddedKafkaConfig, EmbeddedKafkaConfigImpl}
import net.scalytica.kafka.wsproxy.codecs.Decoders._
import net.scalytica.kafka.wsproxy.models._
import org.apache.kafka.clients.consumer.ConsumerConfig.ISOLATION_LEVEL_CONFIG
import org.scalatest.matchers.must.Matchers
import org.scalatest.{Assertion, OptionValues}

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success}

package object test {

  def availablePort: Int = {
    val s = new java.net.ServerSocket(0)
    try {
      s.getLocalPort
    } finally {
      s.close()
    }
  }

  def serverHost(port: Option[Int] = None): String =
    s"localhost${port.map(p => s":$p").getOrElse("")}"

  def registryConfig(
      keySubjNameStrategy: Class[_ <: SubjectNameStrategy] =
        classOf[TopicNameStrategy],
      valSubjNameStrategy: Class[_ <: SubjectNameStrategy] =
        classOf[TopicNameStrategy]
  )(
      implicit schemaRegistryPort: Option[Int] = None
  ): Map[String, _] = {
    schemaRegistryPort
      .map { _ =>
        // scalastyle:off
        Map[String, Any](
          SCHEMA_REGISTRY_URL_CONFIG -> s"http://${serverHost(schemaRegistryPort)}",
          AUTO_REGISTER_SCHEMAS         -> true,
          "key.subject.name.strategy"   -> keySubjNameStrategy.getCanonicalName,
          "value.subject.name.strategy" -> valSubjNameStrategy.getCanonicalName
        )
        // scalastyle:on
      }
      .getOrElse(Map.empty)
  }

  implicit class EmbeddedKafkaCfgExtensions(kcfg: EmbeddedKafkaConfig) {

    def withConsumerReadIsolation(
        isolationLevel: ReadIsolationLevel
    ): EmbeddedKafkaConfig = {
      kcfg
        .asInstanceOf[EmbeddedKafkaConfigImpl]
        .copy(
          customConsumerProperties = Map[String, String](
            ISOLATION_LEVEL_CONFIG -> isolationLevel.value
          )
        )
    }

  }

  implicit class AddFutureAwaitResult[T](future: Future[T]) {

    /** "Safe" Await.result that doesn't throw away half of the stacktrace */
    def awaitResult(atMost: Duration): T = {
      Await.ready(future, atMost)
      future.value.get match {
        case Success(t) => t
        case Failure(ex) =>
          throw new RuntimeException(
            "Trying to await result of failed Future, " +
              "see the cause for the original problem.",
            ex
          )
      }
    }
  }

  implicit class WsProbeExtensions(probe: WSProbe)
      extends Matchers
      with OptionValues {

    def expectWsProducerResultJson(
        expectedTopic: TopicName,
        validateMessageId: Boolean
    )(implicit mat: Materializer): Assertion = {
      probe.expectMessage() match {
        case tm: TextMessage =>
          val collected = tm.textStream
            .grouped(1000) // scalastyle:ignore
            .runWith(Sink.head)
            .awaitResult(5 seconds)
            .reduce(_ + _)

          parse(collected) match {
            case Left(parseError) => throw parseError
            case Right(js) =>
              js.as[WsProducerResult] match {
                case Left(err) => throw err
                case Right(actual) =>
                  if (validateMessageId) actual.clientMessageId must not be None
                  actual.topic mustBe expectedTopic.value
                  actual.offset mustBe >=(0L)
                  actual.partition mustBe >=(0)
              }
          }

        case _ =>
          throw new AssertionError("Expected TextMessage but got BinaryMessage")
      }
    }

    // scalastyle:off method.length
    def expectWsConsumerKeyValueResultJson[K, V](
        expectedTopic: TopicName,
        expectedKey: K,
        expectedValue: V,
        expectHeaders: Boolean = false
    )(
        implicit mat: Materializer,
        kdec: Decoder[K],
        vdec: Decoder[V]
    ): Assertion = {
      probe.inProbe.requestNext(20 seconds) match {
        case tm: TextMessage =>
          val collected = tm.textStream
            .grouped(1000) // scalastyle:ignore
            .runWith(Sink.head)
            .awaitResult(5 seconds)
            .reduce(_ + _)

          parse(collected) match {
            case Left(parseError) => throw parseError
            case Right(js) =>
              js.as[WsConsumerRecord[K, V]] match {
                case Left(err) => throw err
                case Right(actual) =>
                  actual.topic.value mustBe expectedTopic.value
                  actual.offset.value mustBe >=(0L)
                  actual.partition.value mustBe >=(0)

                  if (expectHeaders) {
                    actual.headers must not be None
                    actual.headers.value must have size 1
                    actual.headers.value.headOption.value.key must startWith(
                      "key"
                    )
                    actual.headers.value.headOption.value.value must startWith(
                      "value"
                    )
                  } else {
                    actual.headers mustBe None
                  }

                  actual match {
                    case kvr: ConsumerKeyValueRecord[K, V] =>
                      kvr.key.value mustBe expectedKey
                      kvr.value.value mustBe expectedValue

                    case vr: ConsumerValueRecord[V] =>
                      vr.value.value mustBe expectedValue
                  }
              }
          }

        case _ =>
          throw new AssertionError(
            s"""Expected TextMessage but got BinaryMessage"""
          )
      }
    }

    def expectWsConsumerValueResultJson[V](
        expectedTopic: TopicName,
        expectedValue: V
    )(
        implicit mat: Materializer,
        vdec: Decoder[V]
    ): Assertion = {
      expectWsConsumerKeyValueResultJson[Unit, V](
        expectedTopic = expectedTopic,
        expectedKey = (),
        expectedValue = expectedValue
      )
    }

  }

}
