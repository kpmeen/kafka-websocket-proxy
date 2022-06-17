package net.scalytica.kafka.wsproxy.web

import akka.actor.typed.scaladsl.adapter._
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.stream.Materializer
import akka.util.Timeout
import io.circe.{Json, Printer}
import io.circe.syntax._
import io.circe.parser._
import net.scalytica.kafka.wsproxy.admin.WsKafkaAdminClient
import net.scalytica.kafka.wsproxy.auth.OpenIdClient
import net.scalytica.kafka.wsproxy.codecs.Encoders.{
  brokerInfoEncoder,
  dynamicCfgEncoder
}
import net.scalytica.kafka.wsproxy.codecs.Decoders.dynamicCfgDecoder
import net.scalytica.kafka.wsproxy.config.Configuration.{
  AppCfg,
  ClientSpecificLimitCfg,
  ConsumerSpecificLimitCfg,
  DynamicCfg,
  ProducerSpecificLimitCfg
}
import net.scalytica.kafka.wsproxy.config.{
  DynamicConfigurations,
  RunnableDynamicConfigHandlerRef
}
import net.scalytica.kafka.wsproxy.config.DynamicConfigHandlerImplicits._
import net.scalytica.kafka.wsproxy.config.DynamicConfigHandlerProtocol.{
  ConfigRemoved,
  ConfigSaved,
  DynamicConfigOpResult,
  FoundActiveConfigs,
  IncompleteOp,
  InvalidKey,
  RemovedAllConfigs
}
import net.scalytica.kafka.wsproxy.errors.{ImpossibleError, UnexpectedError}
import net.scalytica.kafka.wsproxy.logging.WithProxyLogger
import net.scalytica.kafka.wsproxy.models.{WsGroupId, WsProducerId}
import net.scalytica.kafka.wsproxy.session.{SessionHandlerRef, SessionUpdated}
import net.scalytica.kafka.wsproxy.session.SessionHandlerImplicits._

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

trait AdminRoutesResponses extends WithProxyLogger { self: BaseRoutes =>
  protected def emptyResponse(status: StatusCode): HttpResponse =
    HttpResponse(
      status = status,
      entity = HttpEntity.empty(ContentTypes.`application/json`)
    )

  protected def okResponse(json: String): HttpResponse =
    HttpResponse(
      status = OK,
      entity = HttpEntity(
        contentType = ContentTypes.`application/json`,
        string = json
      )
    )

  protected lazy val timeoutResponse: HttpResponse =
    HttpResponse(
      status = InternalServerError,
      entity = HttpEntity(
        contentType = ContentTypes.`application/json`,
        string = jsonMessageFromString(
          "The operation to add a new dynamic config timed out."
        )
      )
    )

  protected def invalidRequestResponse(msg: String): HttpResponse =
    HttpResponse(
      status = BadRequest,
      entity = HttpEntity(
        contentType = ContentTypes.`application/json`,
        string = jsonMessageFromString(msg)
      )
    )

  /** Helper function to handler errors when fetching all dynamic configs */
  protected def handlerResRecovery(
      id: Option[String] = None
  ): PartialFunction[Throwable, DynamicConfigOpResult] = { case t: Throwable =>
    val msg = id
      .map(str => s"An error occurred while fetching dynamic configs for $str")
      .getOrElse("An error occurred while fetching dynamic configs.")
    log.error(msg, t)
    IncompleteOp(t.getMessage)
  }

  /** Helper function to handler errors when fetching specific dynamic config */
  protected def optRecovery[U](
      id: Option[String] = None
  ): PartialFunction[Throwable, Option[U]] = { case t: Throwable =>
    val msg = id
      .map(str => s"An error occurred while fetching dynamic configs for $str")
      .getOrElse("An error occurred while fetching dynamic configs.")
    log.error(msg, t)
    None
  }
}

/** Administrative endpoints */
trait AdminRoutes extends AdminRoutesResponses { self: BaseRoutes =>

  /**
   * Fetch the Kafka cluster info and complete the request
   *
   * @param cfg
   *   The [[AppCfg]] to use.
   * @return
   *   The [[Route]] completing the request with an HTTP response containing a
   *   JSON message.
   */
  private[this] def serveClusterInfo(implicit cfg: AppCfg): Route = {
    complete {
      val admin = new WsKafkaAdminClient(cfg)
      try {
        log.debug("Fetching Kafka cluster info...")
        val ci = admin.clusterInfo
        okResponse(ci.asJson.printWith(Printer.spaces2))
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
   * @param staticCons
   *   A {{{Seq[DynamicCfg]}}} with static consumer configs.
   * @param staticProd
   *   A {{{Seq[DynamicCfg]}}} with static producer configs.
   * @param dynCons
   *   A {{{Seq[DynamicCfg]}}} with dynamic consumer configs.
   * @param dynProd
   *   A {{{Seq[DynamicCfg]}}} with dynamic producer configs.
   * @return
   *   A new [[Json]] object.
   */
  private[this] def allConfigsAsJsonStr(
      staticCons: Seq[DynamicCfg],
      staticProd: Seq[DynamicCfg],
      dynCons: Seq[DynamicCfg],
      dynProd: Seq[DynamicCfg]
  ): Json = {
    Json.obj(
      "consumers" -> Json.obj(
        "static"  -> buildConfigListJson(staticCons),
        "dynamic" -> buildConfigListJson(dynCons)
      ),
      "producers" -> Json.obj(
        "static"  -> buildConfigListJson(staticProd),
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
  private[this] def serveAllClientConfigs(
      implicit cfg: AppCfg,
      maybeDynamicCfgHandlerRef: Option[RunnableDynamicConfigHandlerRef],
      mat: Materializer
  ): Route = {
    implicit val handlerTimeout: Timeout = 10 seconds
    implicit val ec                      = mat.executionContext
    implicit val scheduler               = mat.system.toTyped.scheduler

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
                  "Fetching all dynamic configs could not be completed. " +
                    s"Reason: $reason"
                )
                (Seq.empty, Seq.empty)
              case bug =>
                log.error(
                  "Trying to fetch all dynamic configs returned an unexpected" +
                    s" value type: { ${bug.toString} }. This is wrong and " +
                    "should be treated as a bug."
                )
                (Seq.empty, Seq.empty)
            }
            .map { case (dynCons, dynProd) =>
              val json = allConfigsAsJsonStr(
                staticCons = static.consumerLimits,
                staticProd = static.producerLimits,
                dynCons = dynCons,
                dynProd = dynProd
              )

              okResponse(json)
            }
        }(err => failWith(err))
      }
      .getOrElse {
        val json = allConfigsAsJsonStr(
          staticCons = static.consumerLimits,
          staticProd = static.producerLimits,
          dynCons = Seq.empty,
          dynProd = Seq.empty
        )
        complete(okResponse(json))
      }
  }
  // scalastyle:on method.length

  /** Removes ALL dynamic client configurations */
  def removeAllClientConfigs()(
      implicit
      maybeDynamicCfgHandlerRef: Option[RunnableDynamicConfigHandlerRef],
      mat: Materializer
  ): Route = {
    implicit val handlerTimeout: Timeout = 10 seconds
    implicit val ec                      = mat.executionContext
    implicit val scheduler               = mat.system.toTyped.scheduler

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
   *   An akka-http [[Route]]
   */
  private[this] def findClientConfig(id: String, isConsumer: Boolean)(
      implicit cfg: AppCfg,
      maybeDynamicCfgHandlerRef: Option[RunnableDynamicConfigHandlerRef],
      mat: Materializer
  ): Route = {
    implicit val handlerTimeout: Timeout = 10 seconds
    implicit val ec                      = mat.executionContext
    implicit val scheduler               = mat.system.toTyped.scheduler

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
   *   An akka-http [[Route]].
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
        case ConfigSaved(_)     => emptyResponse(OK)
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
  private[this] def addDynamicConfig(dc: DynamicCfg)(
      implicit sessionHandlerRef: SessionHandlerRef,
      maybeDynamicCfgHandlerRef: Option[RunnableDynamicConfigHandlerRef],
      mat: Materializer
  ): Route = {
    implicit val handlerTimeout: Timeout = 10 seconds
    implicit val ec                      = mat.executionContext
    implicit val scheduler               = mat.system.toTyped.scheduler

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
  private[this] def updateDynamicConfig(dc: DynamicCfg)(
      implicit sessionHandlerRef: SessionHandlerRef,
      maybeDynamicCfgHandlerRef: Option[RunnableDynamicConfigHandlerRef],
      mat: Materializer
  ): Route = {
    implicit val handlerTimeout: Timeout = 10 seconds
    implicit val ec                      = mat.executionContext
    implicit val scheduler               = mat.system.toTyped.scheduler

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
  private[this] def decodeAndHandleSave(isConsumer: Boolean)(
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
        case ConfigRemoved(_)   => emptyResponse(OK)
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
  private[this] def removeConsumerConfig(idStr: String)(
      implicit
      maybeDynamicCfgHandlerRef: Option[RunnableDynamicConfigHandlerRef],
      mat: Materializer
  ): Route = {
    implicit val handlerTimeout: Timeout = 10 seconds
    implicit val ec                      = mat.executionContext
    implicit val scheduler               = mat.system.toTyped.scheduler

    maybeDynamicCfgHandlerRef
      .map(h => removeClientConfig(WsGroupId(idStr))(h.removeConsumerConfig))
      .getOrElse(complete(emptyResponse(MethodNotAllowed)))
  }

  /** Route for removing a producer config */
  private[this] def removeProducerConfig(idStr: String)(
      implicit
      maybeDynamicCfgHandlerRef: Option[RunnableDynamicConfigHandlerRef],
      mat: Materializer
  ): Route = {
    implicit val handlerTimeout: Timeout = 10 seconds
    implicit val ec                      = mat.executionContext
    implicit val scheduler               = mat.system.toTyped.scheduler

    maybeDynamicCfgHandlerRef
      .map(h => removeClientConfig(WsProducerId(idStr))(h.removeProducerConfig))
      .getOrElse(complete(emptyResponse(MethodNotAllowed)))
  }

  private[this] def wrongTypeinJson(
      jsonStr: String,
      isConsumer: Boolean
  ): Route = {
    log.debug(s"Dynamic configuration JSON is invalid: $jsonStr.")
    val tmp = "Invalid JSON for %s config."
    val msg = tmp.format(if (isConsumer) "consumer" else "producer")
    complete(invalidRequestResponse(msg))
  }

  /** Routes for handling consumer/producer config related functionality */
  private[this] def clientTypePath(clientType: String, isConsumer: Boolean)(
      implicit cfg: AppCfg,
      sessionHandlerRef: SessionHandlerRef,
      maybeDynamicCfgHandlerRef: Option[RunnableDynamicConfigHandlerRef],
      mat: Materializer
  ): Route = {
    pathPrefix(clientType) {
      concat(
        pathEnd {
          post(decodeAndHandleSave(isConsumer)(addDynamicConfig))
        },
        path(Remaining) { idStr =>
          concat(
            get(findClientConfig(idStr, isConsumer = isConsumer)),
            put(decodeAndHandleSave(isConsumer)(updateDynamicConfig)),
            delete {
              if (isConsumer) removeConsumerConfig(idStr)
              else removeProducerConfig(idStr)
            }
          )
        }
      )
    }
  }

  /**
   * Route specification for administrative endpoints and functionality.
   *
   * @param cfg
   *   The [[AppCfg]] to use.
   * @param maybeDynamicCfgHandlerRef
   *   The [[RunnableDynamicConfigHandlerRef]] that orchestrates the dynamic
   *   configs.
   * @param maybeOidcClient
   *   The [[OpenIdClient]] to use if OIDC is enabled.
   * @return
   *   The Route specification
   */
  def adminRoutes(
      implicit cfg: AppCfg,
      sessionHandlerRef: SessionHandlerRef,
      maybeDynamicCfgHandlerRef: Option[RunnableDynamicConfigHandlerRef],
      maybeOidcClient: Option[OpenIdClient]
  ): Route = {
    extractMaterializer { implicit mat =>
      implicit val sh = sessionHandlerRef.shRef
      handleExceptions(wsExceptionHandler) {
        maybeAuthenticate(cfg, maybeOidcClient, mat) { _ =>
          pathPrefix("admin") {
            concat(
              pathPrefix("kafka") {
                path("info")(get(serveClusterInfo))
              },
              pathPrefix("client") {
                pathPrefix("config") {
                  concat(
                    pathEnd {
                      concat(
                        get(serveAllClientConfigs),
                        delete(removeAllClientConfigs())
                      )
                    },
                    clientTypePath("consumer", isConsumer = true),
                    clientTypePath("producer", isConsumer = false)
                  )
                }
              }
            )
          }
        }
      }
    }
  }
}
