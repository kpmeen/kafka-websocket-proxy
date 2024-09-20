package net.scalytica.kafka.wsproxy.web.admin

import net.scalytica.kafka.wsproxy.auth.OpenIdClient
import net.scalytica.kafka.wsproxy.codecs.Decoders._
import net.scalytica.kafka.wsproxy.config.Configuration._
import net.scalytica.kafka.wsproxy.config.RunnableDynamicConfigHandlerRef
import net.scalytica.kafka.wsproxy.models._
import net.scalytica.kafka.wsproxy.session.SessionHandlerProtocol._
import net.scalytica.kafka.wsproxy.session.SessionHandlerRef
import net.scalytica.kafka.wsproxy.web.BaseRoutes

import io.circe.parser._
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server._
import org.apache.pekko.stream.Materializer

/** Administrative endpoints */
trait AdminRoutes extends AdminRoutesOps { self: BaseRoutes =>

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
          // Add a new dynamic client config
          post(decodeAndHandleSave(isConsumer)(addDynamicConfig))
        },
        path(Remaining) { idStr =>
          concat(
            // Find client configs for the given group.id or producerId
            get(findClientConfig(idStr, isConsumer = isConsumer)),
            // Update dynamic configs for the consumer/producer defined
            // in the request body
            put(decodeAndHandleSave(isConsumer)(updateDynamicConfig)),
            // Delete dynamic configs for the given group.id or producerId
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
   * Route specification for administrating client configurations.
   *
   * @param cfg
   *   The [[AppCfg]] to use.
   * @param sessionHandlerRef
   *   The [[SessionHandlerRef]] that handles all client sessions.
   * @param maybeDynamicCfgHandlerRef
   *   The [[RunnableDynamicConfigHandlerRef]] that orchestrates the dynamic
   *   configs.
   * @param mat
   *   The [[Materializer]] to use.
   * @return
   *   The Route specification
   */
  private[this] def clientConfigPaths(
      implicit cfg: AppCfg,
      sessionHandlerRef: SessionHandlerRef,
      maybeDynamicCfgHandlerRef: Option[RunnableDynamicConfigHandlerRef],
      mat: Materializer
  ) = {
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
  }

  // scalastyle:off method.length
  /**
   * Route specification for administrating consumer groups.
   *
   * @param cfg
   *   The [[AppCfg]] to use.
   * @return
   *   The Route specification
   */
  private[this] def consumerGroupPaths(
      implicit cfg: AppCfg
  ) = {
    pathPrefix("consumer-group") {
      concat(
        path("all") {
          parameters(
            Symbol("activeOnly").as[Boolean] ? false,
            Symbol("includeInternals").as[Boolean] ? false
          ) { (activeOnly, includeInternals) =>
            get(complete(listAllConsumerGroups(activeOnly, includeInternals)))
          }
        },
        pathPrefix(Segment.map(WsGroupId.apply)) { grpId =>
          concat(
            path("describe") {
              get(complete(describeConsumerGroup(grpId)))
            },
            pathPrefix("offsets") {
              concat(
                // get offsets for consumer group
                get(complete(listConsumerGroupOffsets(grpId))),
                // reset / change offsets for consumer group
                pathPrefix("alter") {
                  path(Segment.map(TopicName.apply)) { topic =>
                    put(
                      decodeRequest {
                        entity(as[String]) { jsonStr =>
                          complete(
                            decode[List[PartitionOffsetMetadata]](
                              jsonStr
                            ) match {
                              case Right(poms) =>
                                alterConsumerGroupOffsets(grpId, topic, poms)
                              case Left(err) =>
                                invalidRequestResponse(err.getMessage)
                            }
                          )
                        }
                      }
                    )
                  }
                },
                // delete offsets for consumer group
                pathPrefix("delete") {
                  path(Segment.map(TopicName.apply)) { topic =>
                    delete(complete(deleteConsumerGroupOffsets(grpId, topic)))
                  }
                }
              )
            }
          )
        }
      )
    }
  }
  // scalastyle:on method.length

  /**
   * Route specification for administrative endpoints and functionality.
   *
   * @param cfg
   *   The [[AppCfg]] to use.
   * @param sessionHandlerRef
   *   The [[SessionHandlerRef]] that handles all client sessions.
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
      implicit val sh: ActorRef[SessionProtocol] = sessionHandlerRef.shRef
      handleExceptions(wsExceptionHandler) {
        maybeAuthenticate(cfg, maybeOidcClient, mat) { _ =>
          pathPrefix("admin") {
            concat(
              pathPrefix("kafka") {
                path("info")(get(serveClusterInfo))
              },
              clientConfigPaths,
              consumerGroupPaths
            )
          }
        }
      }
    }
  }
}
