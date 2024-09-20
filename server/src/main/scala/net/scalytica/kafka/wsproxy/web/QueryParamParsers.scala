package net.scalytica.kafka.wsproxy.web

import scala.util.Try

import net.scalytica.kafka.wsproxy.config.Configuration.AppCfg
import net.scalytica.kafka.wsproxy.config.Configuration.ProducerCfg
import net.scalytica.kafka.wsproxy.errors.RequestValidationError
import net.scalytica.kafka.wsproxy.models.Formats._
import net.scalytica.kafka.wsproxy.models._
import net.scalytica.kafka.wsproxy.session.SessionId
import net.scalytica.kafka.wsproxy.web.SocketProtocol._

import org.apache.kafka.clients.consumer.OffsetResetStrategy
import org.apache.pekko.http.scaladsl.model.HttpRequest
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server._
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshaller

trait ParamUnmarshallers {
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
}

object ParamUnmarshallers extends ParamUnmarshallers

trait QueryParamParsers extends ParamUnmarshallers {

  private[this] lazy val ProducerTransactionsDisabledMsg =
    "Unable to provide transactional producer. Server is not configured to " +
      "allow producer transactions."

  private[this] lazy val ProducerInstanceIdRequiredWhenMsg = (why: String) =>
    s"Request param 'instanceId' is required when $why."

  private[this] lazy val ProducerTransactionsDisabledError =
    RequestValidationError(ProducerTransactionsDisabledMsg)

  private[this] lazy val ProducerInstanceIdRequiredError = (why: String) =>
    RequestValidationError(ProducerInstanceIdRequiredWhenMsg(why))

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
   * Shared handling of common [[Rejection]] types from parameter parsing.
   * @param rejections
   *   a collection of [[Rejection]]s
   * @return
   *   a [[Directive]] that complies with the parameter parser
   */
  private[this] def rejectionHandler[ArgType <: SocketArgs](
      rejections: Seq[Rejection]
  ): Directive[Tuple1[ArgType]] = {
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
   *   Directive extracting query parameters for the outbound (consuming) socket
   *   communication.
   */
  def webSocketOutParams: Directive[Tuple1[OutSocketArgs]] =
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
    ).tmap(OutSocketArgs.fromTupledQueryParams)
      .recover[Tuple1[OutSocketArgs]](rejectionHandler)

  // ==================================================================

  private[this] def isInParamsTransNoSessionId(
      tx: Boolean,
      iid: Option[WsProducerInstanceId]
  )(implicit prodCfg: ProducerCfg): Boolean = {
    prodCfg.hasValidTransactionCfg && tx && iid.isEmpty
  }

  private[this] def isInParamsNoInstanceId(
      tx: Boolean,
      iid: Option[WsProducerInstanceId]
  )(implicit prodCfg: ProducerCfg): Boolean = {
    !prodCfg.exactlyOnceEnabled && prodCfg.sessionsEnabled && !tx && iid.isEmpty
  }

  // scalastyle:off cyclomatic.complexity
  /**
   * Parser implementation for incoming query parameters for setting up an
   * inbound WebSocket connection.
   *
   * @param appCfg
   *   The [[AppCfg]] to use
   * @return
   *   Directive extracting query parameters for the inbound (producer) socket
   *   communication.
   */
  def webSocketInParams(appCfg: AppCfg): Directive[Tuple1[InSocketArgs]] = {
    implicit val prodCfg = appCfg.producer

    parameters(
      Symbol("clientId").as[WsProducerId],
      Symbol("instanceId").as[WsProducerInstanceId] ?,
      Symbol("topic").as[TopicName],
      Symbol("socketPayload").as[SocketPayload] ? (JsonPayload: SocketPayload),
      Symbol("keyType").as[FormatType] ?,
      Symbol("valType").as[FormatType] ? (StringType: FormatType),
      Symbol("transactional").as[Boolean] ? false
    ).tmap {
      case (_, _, _, _, _, _, tx) if tx && !prodCfg.hasValidTransactionCfg =>
        throw ProducerTransactionsDisabledError

      case (_, iid, _, _, _, _, tx) if isInParamsTransNoSessionId(tx, iid) =>
        throw ProducerInstanceIdRequiredError("using transactional")

      case (_, iid, _, _, _, _, tx) if isInParamsNoInstanceId(tx, iid) =>
        throw ProducerInstanceIdRequiredError(
          "producer sessions are enabled on the proxy server"
        )

      // When all cases above are OK, the request should be valid
      case tuple => InSocketArgs.fromTupledQueryParams(tuple)

    }.recover[Tuple1[InSocketArgs]](rejectionHandler)
  }

}
