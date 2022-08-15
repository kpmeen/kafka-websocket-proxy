package net.scalytica.kafka.wsproxy.web

import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.http.scaladsl.unmarshalling.Unmarshaller
import net.scalytica.kafka.wsproxy.errors.RequestValidationError
import net.scalytica.kafka.wsproxy.web.SocketProtocol._
import net.scalytica.kafka.wsproxy.models.Formats._
import net.scalytica.kafka.wsproxy.models._
import net.scalytica.kafka.wsproxy.session.SessionId
import org.apache.kafka.clients.consumer.OffsetResetStrategy

import scala.util.Try

trait QueryParamParsers {

  implicit val clientIdUnmarshaller: Unmarshaller[String, WsClientId] =
    Unmarshaller.strict[String, WsClientId] { str =>
      Option(str).map(WsClientId.apply).getOrElse {
        throw Unmarshaller.NoContentException
      }
    }

  implicit val producerIdUnmarshaller: Unmarshaller[String, WsProducerId] =
    Unmarshaller.strict[String, WsProducerId] { str =>
      Option(str).map(WsProducerId.apply).getOrElse {
        throw Unmarshaller.NoContentException
      }
    }

  implicit val pInstIdUnmarshaller: Unmarshaller[String, WsProducerInstanceId] =
    Unmarshaller.strict[String, WsProducerInstanceId] { str =>
      Option(str).map(WsProducerInstanceId.apply).getOrElse {
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

  implicit val isoLevelUnmarshaller: Unmarshaller[String, ReadIsolationLevel] =
    Unmarshaller.strict[String, ReadIsolationLevel] { str =>
      Option(str).map(ReadIsolationLevel.unsafeFromString).getOrElse {
        throw Unmarshaller.NoContentException
      }
    }

  sealed trait ParamError
  case object InvalidPath          extends ParamError
  case object MissingRequiredParam extends ParamError

  case class ConsumerParamError(
      sessionId: SessionId,
      wsClientId: WsClientId,
      wsGroupId: WsGroupId
  ) extends ParamError

  case class ProducerParamError(
      sessionId: SessionId,
      wsProducerId: WsProducerId,
      wsInstanceId: Option[WsProducerInstanceId]
  ) extends ParamError

  def paramsOnError(
      request: HttpRequest
  ): Directive[Tuple1[ParamError]] = {
    if (request.uri.path.endsWith("in", ignoreTrailingSlash = true)) {
      parameters(
        Symbol("clientId").as[WsProducerId] ?,
        Symbol("instanceId").as[WsProducerInstanceId] ?
      ).tmap { t =>
        t._1
          .map[ParamError] { cid =>
            ProducerParamError(
              SessionId.forProducer(None)(cid),
              cid,
              t._2
            )
          }
          .getOrElse(MissingRequiredParam)
      }
    } else if (request.uri.path.endsWith("out", ignoreTrailingSlash = true)) {
      parameters(
        Symbol("clientId").as[WsClientId] ?,
        Symbol("groupId").as[WsGroupId] ?
      ).tmap { t =>
        t._1
          .map[ParamError] { cid =>
            ConsumerParamError(
              SessionId.forConsumer(None)(cid),
              cid,
              WsGroupId.fromOption(t._2)(cid)
            )
          }
          .getOrElse(MissingRequiredParam)
      }
    } else {
      Directives.provide[ParamError](InvalidPath)
    }
  }

  /**
   * @return
   *   Directive extracting query parameters for the outbound (consuming) socket
   *   communication.
   */
  def outParams: Directive[Tuple1[OutSocketArgs]] =
    parameters(
      Symbol("clientId").as[WsClientId],
      Symbol("groupId").as[WsGroupId] ?,
      Symbol("topic").as[TopicName],
      Symbol("socketPayload").as[SocketPayload] ? (JsonPayload: SocketPayload),
      Symbol("keyType").as[FormatType] ?,
      Symbol("valType").as[FormatType] ? (StringType: FormatType),
      Symbol("offsetResetStrategy")
        .as[OffsetResetStrategy] ? OffsetResetStrategy.EARLIEST,
      Symbol("isolationLevel")
        .as[ReadIsolationLevel] ? (ReadUncommitted: ReadIsolationLevel),
      Symbol("rate").as[Int] ?,
      Symbol("batchSize").as[Int] ?,
      Symbol("autoCommit").as[Boolean] ? true
    ).tmap(OutSocketArgs.fromTupledQueryParams).recover { rejections =>
      rejections.headOption
        .map {
          case MissingQueryParamRejection(pname) =>
            throw RequestValidationError(s"Request param '$pname' is missing.")
          case mqpr: MalformedQueryParamRejection =>
            throw RequestValidationError(mqpr.errorMsg)
        }
        .getOrElse(throw RequestValidationError("The request was invalid"))
    }

  /**
   * @return
   *   Directive extracting query parameters for the inbound (producer) socket
   *   communication.
   */
  def inParams: Directive[Tuple1[InSocketArgs]] =
    parameters(
      Symbol("clientId").as[WsProducerId],
      Symbol("instanceId").as[WsProducerInstanceId] ?,
      Symbol("topic").as[TopicName],
      Symbol("socketPayload").as[SocketPayload] ? (JsonPayload: SocketPayload),
      Symbol("keyType").as[FormatType] ?,
      Symbol("valType").as[FormatType] ? (StringType: FormatType)
    ).tmap(InSocketArgs.fromTupledQueryParams).recover { rejections =>
      rejections.headOption
        .map {
          case MissingQueryParamRejection(pname) =>
            throw RequestValidationError(s"Request param '$pname' is missing.")
          case mqpr: MalformedQueryParamRejection =>
            throw RequestValidationError(mqpr.errorMsg)
        }
        .getOrElse(throw RequestValidationError("The request was invalid"))
    }

}
