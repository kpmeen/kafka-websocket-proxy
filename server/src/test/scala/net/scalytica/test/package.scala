package net.scalytica

import akka.http.scaladsl.model.ws.{BinaryMessage, TextMessage}
import akka.http.scaladsl.testkit.WSProbe
import akka.stream.Materializer
import akka.stream.scaladsl.Sink
import io.circe.Decoder
import io.circe.parser.parse
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig.{
  AUTO_REGISTER_SCHEMAS,
  SCHEMA_REGISTRY_URL_CONFIG
}
import io.confluent.kafka.serializers.subject.TopicNameStrategy
import io.confluent.kafka.serializers.subject.strategy.SubjectNameStrategy
import net.scalytica.kafka.wsproxy.avro.SchemaTypes.{
  AvroConsumerRecord,
  AvroProducerResult
}
import net.scalytica.kafka.wsproxy.codecs.Decoders._
import net.scalytica.kafka.wsproxy.codecs.WsProxyAvroSerde
import net.scalytica.kafka.wsproxy.models.Formats.AvroType
import net.scalytica.kafka.wsproxy.models.{
  ConsumerKeyValueRecord,
  ConsumerValueRecord,
  WsConsumerRecord,
  WsProducerResult
}
import net.scalytica.test.TestTypes.{Album, TestKey}
import org.scalatest.matchers.must.Matchers
import org.scalatest.{Assertion, OptionValues}

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success}

package object test {

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
        Map(
          SCHEMA_REGISTRY_URL_CONFIG    -> s"http://${serverHost(schemaRegistryPort)}",
          AUTO_REGISTER_SCHEMAS         -> true,
          "key.subject.name.strategy"   -> keySubjNameStrategy.getCanonicalName,
          "value.subject.name.strategy" -> valSubjNameStrategy.getCanonicalName
        )
      }
      .getOrElse(Map.empty)
  }

  implicit class AddFutureAwaitResult[T](future: Future[T]) {

    /** "Safe" Await.result that doesn't throw away half of the stacktrace */
    def awaitResult(atMost: Duration): T = {
      Await.ready(future, atMost)
      future.value.get match {
        case Success(t) ⇒ t
        case Failure(ex) ⇒
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
        expectedTopic: String
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
                  actual.topic mustBe expectedTopic
                  actual.offset mustBe >=(0L)
                  actual.partition mustBe >=(0)
              }
          }

        case _ =>
          throw new AssertionError("Expected TextMessage but got BinaryMessage")
      }
    }

    def expectWsProducerResultAvro(
        expectedTopic: String
    )(
        implicit mat: Materializer,
        resultSerde: WsProxyAvroSerde[AvroProducerResult]
    ): Assertion = {
      probe.expectMessage() match {
        case bm: BinaryMessage =>
          val collected = bm.dataStream
            .grouped(1000) // scalastyle:ignore
            .runWith(Sink.head)
            .awaitResult(5 seconds)
            .reduce(_ ++ _)

          val actual = resultSerde.deserialize(collected.toArray)

          actual.topic mustBe expectedTopic
          actual.offset mustBe >=(0L)
          actual.partition mustBe >=(0)

        case _ =>
          throw new AssertionError("Expected BinaryMessage but got TextMessage")
      }
    }

    // scalastyle:off method.length
    def expectWsConsumerKeyValueResultJson[K, V](
        expectedTopic: String,
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
                  actual.topic.value mustBe expectedTopic
                  actual.offset.value mustBe >=(0L)
                  actual.partition.value mustBe >=(0)

                  if (expectHeaders) {
                    actual.headers must not be empty
                    actual.headers.value must have size 1
                    actual.headers.value.headOption.value.key must startWith(
                      "key"
                    )
                    actual.headers.value.headOption.value.value must startWith(
                      "value"
                    )
                  } else {
                    actual.headers mustBe empty
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

    def expectWsConsumerKeyValueResultAvro(
        expectedTopic: String,
        expectedKey: Option[TestKey],
        expectedValue: Album,
        expectHeaders: Boolean = false
    )(
        implicit mat: Materializer,
        crSerde: WsProxyAvroSerde[AvroConsumerRecord]
    ): Assertion = {
      val keySerdes = TestTypes.TestSerdes.keySerdes
      val valSerdes = TestTypes.TestSerdes.valueSerdes

      probe.inProbe.requestNext(20 seconds) match {
        case bm: BinaryMessage =>
          val collected = bm.dataStream
            .grouped(1000) // scalastyle:ignore
            .runWith(Sink.head)
            .awaitResult(5 seconds)
            .reduce(_ ++ _)

          val actual = WsConsumerRecord.fromAvro(
            crSerde.deserialize(collected.toArray)
          )(AvroType)

          actual.topic.value mustBe expectedTopic
          actual.offset.value mustBe >=(0L)
          actual.partition.value mustBe >=(0)

          if (expectHeaders) {
            actual.headers must not be empty
            actual.headers.value must have size 1
            actual.headers.value.headOption.value.key must startWith("key")
            actual.headers.value.headOption.value.value must startWith("value")
          } else {
            actual.headers mustBe empty
          }

          actual match {
            case ConsumerKeyValueRecord(_, _, _, _, _, keyOut, valOut, _) =>
              val k = keySerdes.deserialize(expectedTopic, keyOut.value)
              val v = valSerdes.deserialize(expectedTopic, valOut.value)
              k.username mustBe expectedKey.get.username
              v.title mustBe expectedValue.title
              v.artist mustBe expectedValue.artist
              v.tracks must have size expectedValue.tracks.size.toLong
              v.tracks must contain allElementsOf expectedValue.tracks

            case ConsumerValueRecord(_, _, _, _, _, valOut, _) =>
              val v = valSerdes.deserialize(expectedTopic, valOut.value)
              v mustBe expectedValue
          }

        case _ =>
          throw new AssertionError(
            s"""Expected BinaryMessage but got TextMessage"""
          )
      }
    }
    // scalastyle:on method.length

    def expectWsConsumerValueResultJson[V](
        expectedTopic: String,
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

    def expectWsConsumerValueResultAvro(
        expectedTopic: String,
        expectedValue: Album
    )(
        implicit mat: Materializer,
        crSerde: WsProxyAvroSerde[AvroConsumerRecord]
    ): Assertion = {
      expectWsConsumerKeyValueResultAvro(
        expectedTopic = expectedTopic,
        expectedKey = None,
        expectedValue = expectedValue
      )
    }

  }

}
