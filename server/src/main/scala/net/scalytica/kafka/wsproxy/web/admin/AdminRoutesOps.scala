package net.scalytica.kafka.wsproxy.web.admin

import org.apache.pekko.actor.typed.scaladsl.adapter._
import org.apache.pekko.http.scaladsl.model.StatusCodes._
import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server._
import org.apache.pekko.stream.Materializer
import org.apache.pekko.util.Timeout
import io.circe.Json
import io.circe.syntax._
import io.circe.parser._
import net.scalytica.kafka.wsproxy.admin.WsKafkaAdminClient
import net.scalytica.kafka.wsproxy.codecs.Encoders._
import net.scalytica.kafka.wsproxy.codecs.Decoders._
import net.scalytica.kafka.wsproxy.config.Configuration._
import net.scalytica.kafka.wsproxy.config.{
  DynamicConfigurations,
  RunnableDynamicConfigHandlerRef
}
import net.scalytica.kafka.wsproxy.config.DynamicConfigHandlerImplicits._
import net.scalytica.kafka.wsproxy.config.DynamicConfigHandlerProtocol._
import net.scalytica.kafka.wsproxy.errors.{ImpossibleError, UnexpectedError}
import net.scalytica.kafka.wsproxy.models._
import net.scalytica.kafka.wsproxy.session.{SessionHandlerRef, SessionUpdated}
import net.scalytica.kafka.wsproxy.web.BaseRoutes
import net.scalytica.kafka.wsproxy.session.SessionHandlerImplicits._
import org.apache.pekko.actor.typed.Scheduler

import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import scala.concurrent.duration._

trait AdminRoutesOps extends AdminRoutesResponses { self: BaseRoutes =>

  /**
   * Fetch the Kafka cluster info and complete the request
   *
   * @param cfg
   *   The [[AppCfg]] to use.
   * @return
   *   The [[Route]] completing the request with an HTTP response containing a
   *   JSON message.
   */
  protected[this] def serveClusterInfo(implicit cfg: AppCfg): Route = {
    complete {
      val admin = new WsKafkaAdminClient(cfg)
      try {
        log.debug("Fetching Kafka cluster info...")
        val ci = admin.clusterInfo
        okResponse(ci.asJson.spaces2)
      } finally {
        admin.close()
      }
    }
  }

  /**
   * Function to partition dynamic configurations into one consumer and one
   * producer list.
   * @param dc
   *   The [[DynamicConfigOpResult]] to process.
   * @return
   *   A tuple of {{{(Seq[DynamicCfg], Seq[DynamicCfg])}}}. The configs on the
   *   left side are for consumer, and the right side for producers.
   */
  private[this] def partitionDynamicCfgs(
      dc: DynamicConfigurations
  ): (Seq[DynamicCfg], Seq[DynamicCfg]) = {
    val (cons, prod) =
      dc.values.filter { case _: ClientSpecificLimitCfg => true }.partition {
        case _: ConsumerSpecificLimitCfg => true
        case _: ProducerSpecificLimitCfg => false
        case _ =>
          throw ImpossibleError("Encountered an impossible situation")
      }
    (cons, prod)
  }

  /**
   * Builds the JSON string to return when the client config endpoint needs to
   * return all configs.
   *
   * @param static
   *   A {{{AllClientSpecifcLimits}}} containing all static configs.
   * @param dynCons
   *   A {{{Seq[DynamicCfg]}}} with dynamic consumer configs.
   * @param dynProd
   *   A {{{Seq[DynamicCfg]}}} with dynamic producer configs.
   * @return
   *   A new [[Json]] object.
   */
  private[this] def allConfigsAsJsonStr(
      static: AllClientSpecifcLimits,
      dynCons: Seq[DynamicCfg] = Seq.empty,
      dynProd: Seq[DynamicCfg] = Seq.empty
  ): Json = {
    Json.obj(
      "consumers" -> Json.obj(
        "static"  -> buildConfigListJson(static.consumerLimits),
        "dynamic" -> buildConfigListJson(dynCons)
      ),
      "producers" -> Json.obj(
        "static"  -> buildConfigListJson(static.producerLimits),
        "dynamic" -> buildConfigListJson(dynProd)
      )
    )
  }

  private[this] def buildConfigListJson(cfgs: Seq[DynamicCfg]): Json =
    Json.arr(cfgs.map(_.asJson): _*)

  /**
   * Gather and aggregate client specific configurations from both static and
   * dynamic configurations.
   *
   * @param cfg
   *   The [[AppCfg]] to use.
   * @param maybeDynamicCfgHandlerRef
   *   The [[RunnableDynamicConfigHandlerRef]] that orchestrates the dynamic
   *   configs.
   * @return
   *   The [[Route]] completing the request with an HTTP response containing a
   *   JSON message
   */
  // scalastyle:off method.length
  protected[this] def serveAllClientConfigs(
      implicit cfg: AppCfg,
      maybeDynamicCfgHandlerRef: Option[RunnableDynamicConfigHandlerRef],
      mat: Materializer
  ): Route = {
    implicit val handlerTimeout: Timeout      = 10 seconds
    implicit val ec: ExecutionContextExecutor = mat.executionContext
    implicit val scheduler: Scheduler         = mat.system.toTyped.scheduler

    val static = cfg.allClientLimits

    maybeDynamicCfgHandlerRef
      .map { handler =>
        completeOrRecoverWith {
          handler
            .getAllConfigs()
            .recover(handlerResRecovery())
            .map {
              case FoundActiveConfigs(dc) =>
                partitionDynamicCfgs(dc)

              case IncompleteOp(reason, _) =>
                log.warn(
                  s"Unable to retrieve all dynamic configs. Reason: $reason"
                )
                (Seq.empty, Seq.empty)
              case bug =>
                log.error(
                  "Fetching all dynamic configs returned an unexpected" +
                    s" value type: { ${bug.toString} }. This is incorrect " +
                    "and should be treated as a bug."
                )
                (Seq.empty, Seq.empty)
            }
            .map { case (dynCons, dynProd) =>
              val js = allConfigsAsJsonStr(static, dynCons, dynProd)
              okResponse(js)
            }
        }(err => failWith(err))
      }
      .getOrElse {
        val json = allConfigsAsJsonStr(static)
        complete(okResponse(json))
      }
  }
  // scalastyle:on method.length

  /** Removes ALL dynamic client configurations */
  protected[this] def removeAllClientConfigs()(
      implicit
      maybeDynamicCfgHandlerRef: Option[RunnableDynamicConfigHandlerRef],
      mat: Materializer
  ): Route = {
    implicit val handlerTimeout: Timeout      = 10 seconds
    implicit val ec: ExecutionContextExecutor = mat.executionContext
    implicit val scheduler: Scheduler         = mat.system.toTyped.scheduler

    maybeDynamicCfgHandlerRef
      .map { handler =>
        completeOrRecoverWith {
          handler.removeAllConfigs().map {
            case RemovedAllConfigs() => emptyResponse(OK)
            case IncompleteOp(_, _)  => timeoutResponse
            case wrong =>
              log.error(
                s"Got unexpected response [$wrong] from dynamic config handler."
              )
              throw UnexpectedError(
                "An unexpected error occurred when trying to remove all configs"
              )
          }
        }(err => failWith(err))
      }
      .getOrElse(complete(emptyResponse(MethodNotAllowed)))
  }

  /**
   * Will either return an HttpResponse with status OK containing the config
   * JSON, or an empty response with NotFound.
   *
   * @param maybeStatic
   *   The option that may contain static configs.
   * @return
   *   a [[HttpResponse]]
   */
  private[this] def maybeStaticConfigResponse(
      maybeStatic: Option[ClientSpecificLimitCfg]
  ): HttpResponse = {
    maybeStatic
      .map(staticCfg => okResponse(staticCfg.asHoconString(useJson = true)))
      .getOrElse(emptyResponse(NotFound))
  }

  /**
   * Try to find a client specific config. If there is a dynamic config
   * available, it will have precedence over a static config.
   *
   * @param id
   *   The client identifier to look for.
   * @param isConsumer
   *   If true, will look for consumer client config, if false will look for a
   *   producer client config.
   * @param cfg
   *   The [[AppCfg]] to use
   * @param maybeDynamicCfgHandlerRef
   *   If dynamic configs are enabled, will contain the reference to the
   *   handler.
   * @param mat
   *   The [[Materializer]] to use.
   * @return
   *   A pekko-http [[Route]]
   */
  protected[this] def findClientConfig(id: String, isConsumer: Boolean)(
      implicit cfg: AppCfg,
      maybeDynamicCfgHandlerRef: Option[RunnableDynamicConfigHandlerRef],
      mat: Materializer
  ): Route = {
    implicit val handlerTimeout: Timeout      = 10 seconds
    implicit val ec: ExecutionContextExecutor = mat.executionContext
    implicit val scheduler: Scheduler         = mat.system.toTyped.scheduler

    val maybeStatic = {
      if (isConsumer) cfg.allClientLimits.findConsumerSpecificLimitCfg(id)
      else cfg.allClientLimits.findProducerSpecificLimitCfg(id)
    }

    maybeDynamicCfgHandlerRef
      .map { handler =>
        completeOrRecoverWith {
          val res: Future[Option[ClientSpecificLimitCfg]] =
            if (isConsumer) {
              handler
                .findConfigForConsumer(WsGroupId(id))
                .recover(optRecovery(Option(id)))
            } else {
              handler
                .findConfigForProducer(WsProducerId(id))
                .recover(optRecovery(Option(id)))
            }

          res.map {
            case Some(theCfg) =>
              okResponse(theCfg.asHoconString(useJson = true))

            case None =>
              maybeStaticConfigResponse(maybeStatic)
          }
        }(err => failWith(err))
      }
      .getOrElse {
        complete(maybeStaticConfigResponse(maybeStatic))
      }
  }

  /**
   * Function to save (add or update) dynamic configs.
   *
   * @param dc
   *   The config to save.
   * @param save
   *   The function to use for saving data.
   * @param ec
   *   The [[ExecutionContext]] to use.
   * @return
   *   A pekko-http [[Route]].
   */
  private[this] def saveDynamicConfig(
      dc: DynamicCfg
  )(
      save: DynamicCfg => Future[DynamicConfigOpResult]
  )(
      implicit ec: ExecutionContext
  ): Route = {
    completeOrRecoverWith {
      save(dc).map {
        case ConfigSaved(_) => emptyResponse(OK)
        case ConfigNotFound(key) =>
          log.debug(s"Dynamic config for $key could not be found")
          emptyResponse(NotFound)
        case IncompleteOp(_, _) => timeoutResponse
        case InvalidKey(_) =>
          invalidRequestResponse("The given id is not valid.")
        case wrong =>
          log.error(
            s"Got unexpected response [$wrong] from dynamic config handler."
          )
          throw UnexpectedError(
            "An unexpected error occurred when trying to save all configs"
          )
      }
    }(err => failWith(err))
  }

  /** Add a new dynamic config */
  protected[this] def addDynamicConfig(dc: DynamicCfg)(
      implicit sessionHandlerRef: SessionHandlerRef,
      maybeDynamicCfgHandlerRef: Option[RunnableDynamicConfigHandlerRef],
      mat: Materializer
  ): Route = {
    implicit val handlerTimeout: Timeout      = 10 seconds
    implicit val ec: ExecutionContextExecutor = mat.executionContext
    implicit val scheduler: Scheduler         = mat.system.toTyped.scheduler

    maybeDynamicCfgHandlerRef
      .map { handler =>
        saveDynamicConfig(dc) {
          case csl: ClientSpecificLimitCfg =>
            handler.addConfig(csl).flatMap {
              case cs: ConfigSaved =>
                sessionHandlerRef.shRef.updateSessionConfig(csl).map {
                  case SessionUpdated(_) => cs
                  case wrong =>
                    log.warn(s"Session config not updated $wrong")
                    cs
                }

              case notSaved =>
                log.warn(s"Config not added $notSaved")
                Future.successful(notSaved)
            }

          case other =>
            handler.addConfig(other)
        }
      }
      .getOrElse(complete(emptyResponse(MethodNotAllowed)))
  }

  /** Update an existing dynamic config */
  protected[this] def updateDynamicConfig(dc: DynamicCfg)(
      implicit sessionHandlerRef: SessionHandlerRef,
      maybeDynamicCfgHandlerRef: Option[RunnableDynamicConfigHandlerRef],
      mat: Materializer
  ): Route = {
    implicit val handlerTimeout: Timeout      = 10 seconds
    implicit val ec: ExecutionContextExecutor = mat.executionContext
    implicit val scheduler: Scheduler         = mat.system.toTyped.scheduler

    maybeDynamicCfgHandlerRef
      .map { handler =>
        saveDynamicConfig(dc) {
          case csl: ClientSpecificLimitCfg =>
            handler.updateConfig(csl).flatMap {
              case cs: ConfigSaved =>
                sessionHandlerRef.shRef.updateSessionConfig(csl).map {
                  case SessionUpdated(_) => cs
                  case wrong =>
                    log.warn(s"Session config not updated $wrong")
                    cs
                }

              case notSaved =>
                log.warn(s"Config not updated $notSaved")
                Future.successful(notSaved)
            }

          case other =>
            handler.updateConfig(other)
        }
      }
      .getOrElse(complete(emptyResponse(MethodNotAllowed)))
  }

  /**
   * Function for decoding a request body and save the content using the given
   * {{{save}}} function.
   */
  protected[this] def decodeAndHandleSave(isConsumer: Boolean)(
      save: DynamicCfg => Route
  ): Route = {
    decodeRequest {
      entity(as[String]) { jsonStr =>
        decode[DynamicCfg](jsonStr) match {
          case Right(dc) =>
            dc match {
              case csl: ConsumerSpecificLimitCfg =>
                if (isConsumer) save(csl)
                else wrongTypeinJson(jsonStr, isConsumer)

              case psl: ProducerSpecificLimitCfg =>
                if (!isConsumer) save(psl)
                else wrongTypeinJson(jsonStr, isConsumer)
            }

          case Left(_) =>
            wrongTypeinJson(jsonStr, isConsumer)
        }
      }
    }
  }

  /** Removes a dynamic client configuration with the given id. */
  private[this] def removeClientConfig[ID](
      id: ID
  )(
      remove: ID => Future[DynamicConfigOpResult]
  )(
      implicit ec: ExecutionContext
  ): Route = {
    completeOrRecoverWith {
      remove(id).map {
        case ConfigRemoved(_) => emptyResponse(OK)
        case ConfigNotFound(key) =>
          log.debug(s"Dynamic config for $key could not be found")
          emptyResponse(NotFound)
        case IncompleteOp(_, _) => timeoutResponse
        case InvalidKey(_) =>
          invalidRequestResponse("The given id is not valid.")
        case wrong =>
          log.error(
            s"Got unexpected response [$wrong] from dynamic config handler."
          )
          throw UnexpectedError(
            "An unexpected error occurred when trying to save all configs"
          )
      }
    }(err => failWith(err))
  }

  /** Route for removing a consumer config */
  protected[this] def removeConsumerConfig(idStr: String)(
      implicit
      maybeDynamicCfgHandlerRef: Option[RunnableDynamicConfigHandlerRef],
      mat: Materializer
  ): Route = {
    implicit val handlerTimeout: Timeout      = 10 seconds
    implicit val ec: ExecutionContextExecutor = mat.executionContext
    implicit val scheduler: Scheduler         = mat.system.toTyped.scheduler

    maybeDynamicCfgHandlerRef
      .map(h => removeClientConfig(WsGroupId(idStr))(h.removeConsumerConfig))
      .getOrElse(complete(emptyResponse(MethodNotAllowed)))
  }

  /** Route for removing a producer config */
  protected[this] def removeProducerConfig(idStr: String)(
      implicit
      maybeDynamicCfgHandlerRef: Option[RunnableDynamicConfigHandlerRef],
      mat: Materializer
  ): Route = {
    implicit val handlerTimeout: Timeout      = 10 seconds
    implicit val ec: ExecutionContextExecutor = mat.executionContext
    implicit val scheduler: Scheduler         = mat.system.toTyped.scheduler

    maybeDynamicCfgHandlerRef
      .map(h => removeClientConfig(WsProducerId(idStr))(h.removeProducerConfig))
      .getOrElse(complete(emptyResponse(MethodNotAllowed)))
  }

  /**
   * Function for building a specific error message indicating a badly formed
   * JSON message.
   *
   * @param jsonStr
   *   String containing the badly formed JSON message.
   * @param isConsumer
   *   Boolean indicating whether the message is intended for a consumer or a
   *   producer
   */
  private[this] def wrongTypeinJson(
      jsonStr: String,
      isConsumer: Boolean
  ): Route = {
    log.debug(s"Dynamic configuration JSON is invalid: $jsonStr.")
    val tmp = "Invalid JSON for %s config."
    val msg = tmp.format(if (isConsumer) "consumer" else "producer")
    complete(invalidRequestResponse(msg))
  }

  /**
   * Function for fetching all consumer groups. If '''activeOnly''' is
   * '''true''', only active consumer groups are returned.
   *
   * @param activeOnly
   *   Flag to indicate if all consumer groups should be returned, or only the
   *   ones that are active.
   * @param includeInternals
   *   Flag to determine if internal ws-proxy consumer groups for sessions and
   *   config handling should be included in the response.
   * @param cfg
   *   The [[AppCfg]] to use.
   * @return
   *   A [[HttpResponse]] with the result in JSON format
   */
  protected[this] def listAllConsumerGroups(
      activeOnly: Boolean,
      includeInternals: Boolean
  )(
      implicit cfg: AppCfg
  ): HttpResponse = {
    val admin = new WsKafkaAdminClient(cfg)
    try {
      log.debug(s"Fetching list of consumer groups (activeOnly is $activeOnly)")
      val cgroups = admin.listConsumerGroups(activeOnly, includeInternals)
      okResponse(cgroups.asJson.dropEmptyValues.deepDropNullValues.spaces2)
    } finally {
      admin.close()
    }
  }

  /**
   * Will try to fetch the Kafka description of the given [[WsGroupId]].
   * @param grpId
   *   [[WsGroupId]] to describe
   * @param cfg
   *   Implicitly provided [[AppCfg]]
   * @return
   *   A [[HttpResponse]]
   */
  protected[this] def describeConsumerGroup(grpId: WsGroupId)(
      implicit cfg: AppCfg
  ): HttpResponse = {
    val admin = new WsKafkaAdminClient(cfg)
    try {
      log.debug(s"Fetching describe info for consumer group ${grpId.value}")
      val maybeCg = admin.describeConsumerGroup(grpId)
      val res = maybeCg
        .map(cg => okResponse(cg.asJson.spaces2))
        .getOrElse(emptyResponse(NotFound))
      res
    } finally {
      admin.close()
    }
  }

  /**
   * Try to list the consumer offsets for the given [[WsGroupId]]
   *
   * @param grpId
   *   [[WsGroupId]] to fetch offsets for
   * @param cfg
   *   Implicitly provided [[AppCfg]]
   * @return
   *   A [[HttpResponse]]
   */
  protected[this] def listConsumerGroupOffsets(grpId: WsGroupId)(
      implicit cfg: AppCfg
  ): HttpResponse = {
    val admin = new WsKafkaAdminClient(cfg)
    try {
      log.debug(s"Fetching offsets for consumer group ${grpId.value}")
      val offsets = admin.listConsumerGroupOffsets(grpId)
      if (offsets.isEmpty) emptyResponse(NotFound)
      else okResponse(offsets.asJson.spaces2)
    } finally {
      admin.close()
    }
  }

  /**
   * Function to modify consumer group offsets.
   *
   * @param grpId
   *   The [[WsGroupId]] to modify
   * @param operation
   *   The function to perform on the [[ConsumerGroup]]
   * @param admin
   *   The [[WsKafkaAdminClient]] to use
   * @return
   *   A [[HttpResponse]]
   */
  private[this] def modifyConsumerGroupOffsets(
      grpId: WsGroupId
  )(
      operation: ConsumerGroup => HttpResponse
  )(implicit admin: WsKafkaAdminClient): HttpResponse = {
    admin
      .describeConsumerGroup(grpId)
      .map { cg =>
        if (!cg.isActive) operation(cg)
        else
          invalidRequestResponse(
            msg = "Unable to modify the consumer group offsets because" +
              " the group is still considered to be active. Please " +
              "ensure that all consumer clients are stopped before " +
              "trying again.",
            statusCode = PreconditionFailed
          )
      }
      .getOrElse {
        invalidRequestResponse(
          msg = s"Unable to locate consumer group with group.id=${grpId.value}",
          statusCode = NotFound
        )
      }
  }

  /**
   * Try to alter which offsets to the consumer group should start consuming
   * from when starting up. For this to succeed, all consumer member instances
   * must be stopped.
   *
   * @param grpId
   *   The [[WsGroupId]] to alter offsets for.
   * @param topic
   *   The [[TopicName]] to alter offsets for.
   * @param poms
   *   A list of [[PartitionOffsetMetadata]] containing the new offsets to use.
   * @param cfg
   *   The [[AppCfg]] to use
   * @return
   *   A [[HttpResponse]]
   */
  protected[this] def alterConsumerGroupOffsets(
      grpId: WsGroupId,
      topic: TopicName,
      poms: List[PartitionOffsetMetadata]
  )(
      implicit cfg: AppCfg
  ): HttpResponse = {
    implicit val admin: WsKafkaAdminClient = new WsKafkaAdminClient(cfg)
    try {
      log.debug(
        "Trying to alter offsets for" +
          s"consumer group ${grpId.value} on topic ${topic.value}"
      )
      modifyConsumerGroupOffsets(grpId) { _ =>
        admin.alterConsumerGroupOffsets(grpId, poms)
        val newOffsets = admin.listConsumerGroupOffsets(grpId)
        okResponse(newOffsets.asJson.spaces2)
      }
    } finally {
      admin.close()
    }
  }

  /**
   * Try to delete all consumer offsets for the given consumer group. For this
   * to succeed, all consumer member instances must be stopped.
   *
   * @param grpId
   *   The [[WsGroupId]] to delete offsets for.
   * @param topic
   *   The [[TopicName]] to delete offsets for.
   * @param cfg
   *   The [[AppCfg]] to use
   * @return
   *   A [[HttpResponse]]
   */
  protected[this] def deleteConsumerGroupOffsets(
      grpId: WsGroupId,
      topic: TopicName
  )(
      implicit cfg: AppCfg
  ): HttpResponse = {
    implicit val admin: WsKafkaAdminClient = new WsKafkaAdminClient(cfg)
    try {
      log.debug(
        "Trying to delete offsets for consumer group " +
          s"${grpId.value} on topic ${topic.value}"
      )
      modifyConsumerGroupOffsets(grpId) { _ =>
        admin.deleteConsumerGroupOffsets(grpId, topic)
        emptyResponse(OK)
      }
    } finally {
      admin.close()
    }
  }

}
