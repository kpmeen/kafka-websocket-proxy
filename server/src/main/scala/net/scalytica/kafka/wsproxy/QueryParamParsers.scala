package net.scalytica.kafka.wsproxy

import akka.http.scaladsl.server._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.unmarshalling.Unmarshaller
import net.scalytica.kafka.wsproxy.SocketProtocol._
import net.scalytica.kafka.wsproxy.models.Formats._
import net.scalytica.kafka.wsproxy.models.{InSocketArgs, OutSocketArgs}
import org.apache.kafka.clients.consumer.OffsetResetStrategy

import scala.util.Try

trait QueryParamParsers {

  // Unmarshaller for SocketPayload query params
  implicit val socketPayloadUnmarshaller: Unmarshaller[String, SocketPayload] =
    Unmarshaller.strict[String, SocketPayload] { str =>
      Option(str).map(SocketPayload.unsafeFromString).getOrElse {
        throw Unmarshaller.NoContentException
      }
    }

  // Unmarshaller for FormatType query params
  implicit val formatTypeUnmarshaller: Unmarshaller[String, FormatType] =
    Unmarshaller.strict[String, FormatType] { str =>
      Option(str).map(FormatType.unsafeFromString).getOrElse {
        throw Unmarshaller.NoContentException
      }
    }

  // Unmarshaller for OffsetResetStrategy params
  implicit val offResetUnmarshaller: Unmarshaller[String, OffsetResetStrategy] =
    Unmarshaller.strict[String, OffsetResetStrategy] { str =>
      Try(OffsetResetStrategy.valueOf(str.toUpperCase)).getOrElse {
        throw new IllegalArgumentException(
          s"$str is not a valid offset reset strategy"
        )
      }
    }

  /**
   * @return Directive extracting query parameters for the outbound (consuming)
   *         socket communication.
   */
  def outParams: Directive[Tuple1[OutSocketArgs]] =
    parameters(
      (
        'clientId,
        'groupId ?,
        'topic,
        'socketPayload.as[SocketPayload] ? (JsonPayload: SocketPayload),
        'keyType.as[FormatType] ?,
        'valType.as[FormatType] ? (StringType: FormatType),
        'offsetResetStrategy
          .as[OffsetResetStrategy] ? OffsetResetStrategy.EARLIEST,
        'rate.as[Int] ?,
        'batchSize.as[Int] ?,
        'autoCommit.as[Boolean] ? true
      )
    ).tmap { t =>
      OutSocketArgs.fromQueryParams(
        clientId = t._1,
        groupId = t._2,
        topicName = t._3,
        socketPayload = t._4,
        keyTpe = t._5,
        valTpe = t._6,
        offsetResetStrategy = t._7,
        rateLimit = t._8,
        batchSize = t._9,
        autoCommit = t._10
      )
    }

  /**
   * @return Directive extracting query parameters for the inbound (producer)
   *         socket communication.
   */
  def inParams: Directive[Tuple1[InSocketArgs]] =
    parameters(
      (
        'topic,
        'socketPayload.as[SocketPayload] ? (JsonPayload: SocketPayload),
        'keyType.as[FormatType] ?,
        'valType.as[FormatType] ? (StringType: FormatType)
      )
    ).tmap(t => InSocketArgs.fromQueryParams(t._1, t._2, t._3, t._4))

}
