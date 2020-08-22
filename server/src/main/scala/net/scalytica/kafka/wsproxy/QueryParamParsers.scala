package net.scalytica.kafka.wsproxy

import akka.http.scaladsl.server._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.unmarshalling.Unmarshaller
import net.scalytica.kafka.wsproxy.SocketProtocol._
import net.scalytica.kafka.wsproxy.models.Formats._
import net.scalytica.kafka.wsproxy.models.{
  InSocketArgs,
  OutSocketArgs,
  TopicName,
  WsClientId,
  WsGroupId
}
import org.apache.kafka.clients.consumer.OffsetResetStrategy

import scala.util.Try

trait QueryParamParsers {

  implicit val clientIdUnmarshaller: Unmarshaller[String, WsClientId] =
    Unmarshaller.strict[String, WsClientId] { str =>
      Option(str).map(WsClientId.apply).getOrElse {
        throw Unmarshaller.NoContentException
      }
    }

  implicit val groupIdUnmarshaller: Unmarshaller[String, WsGroupId] =
    Unmarshaller.strict[String, WsGroupId] { str =>
      Option(str).map(WsGroupId.apply).getOrElse {
        throw Unmarshaller.NoContentException
      }
    }

  implicit val topicNameUnmarshaller: Unmarshaller[String, TopicName] =
    Unmarshaller.strict[String, TopicName] { str =>
      Option(str).map(TopicName.apply).getOrElse {
        throw Unmarshaller.NoContentException
      }
    }

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

  def paramsOnError: Directive[Tuple1[Option[(WsClientId, WsGroupId)]]] = {
    parameters(
      (
        'clientId.as[WsClientId] ?,
        'groupId.as[WsGroupId] ?
      )
    ).tmap(t => t._1.map(cid => (cid, WsGroupId.fromOption(t._2)(cid))))
  }

  /**
   * @return
   *   Directive extracting query parameters for the outbound (consuming) socket
   *   communication.
   */
  def outParams: Directive[Tuple1[OutSocketArgs]] =
    parameters(
      (
        'clientId.as[WsClientId],
        'groupId.as[WsGroupId] ?,
        'topic.as[TopicName],
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
        topic = t._3,
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
   * @return
   *   Directive extracting query parameters for the inbound (producer) socket
   *   communication.
   */
  def inParams: Directive[Tuple1[InSocketArgs]] =
    parameters(
      (
        'topic.as[TopicName],
        'socketPayload.as[SocketPayload] ? (JsonPayload: SocketPayload),
        'keyType.as[FormatType] ?,
        'valType.as[FormatType] ? (StringType: FormatType)
      )
    ).tmap(t => InSocketArgs.fromQueryParams(t._1, t._2, t._3, t._4))

}
