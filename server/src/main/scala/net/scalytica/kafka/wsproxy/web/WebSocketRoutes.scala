package net.scalytica.kafka.wsproxy.web

import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server._
import net.scalytica.kafka.wsproxy.admin.WsKafkaAdminClient
import net.scalytica.kafka.wsproxy.auth.OpenIdClient
import net.scalytica.kafka.wsproxy.config.Configuration.AppCfg
import net.scalytica.kafka.wsproxy.models._
import net.scalytica.kafka.wsproxy.web.Headers.XKafkaAuthHeader

import scala.util.Try

trait WebSocketRoutes { self: BaseRoutes =>

  /**
   * Uses the [[WsKafkaAdminClient]] to check if a given [[TopicName]] exists,
   * and makes sure to close the admin client when done.
   *
   * @param topic
   *   The [[TopicName]] to check for
   * @param cfg
   *   The [[AppCfg]] to use
   * @return
   *   true if the topic exists, otherwise false
   */
  private[this] def checkTopicExists(
      topic: TopicName
  )(implicit cfg: AppCfg): Boolean = {
    log.trace(s"Verifying if topic ${topic.value} exists...")
    val admin = new WsKafkaAdminClient(cfg)

    try {
      Try(admin.topicExists(topic)) match {
        case scala.util.Success(v) => v
        case scala.util.Failure(t) =>
          log.warn(
            s"An error occurred while checking if topic $topic exists",
            t
          )
          false
      }
    } finally {
      admin.close()
    }
  }

  /**
   * @param args
   *   The socket args provided
   * @param webSocketHandler
   *   lazy initializer for the websocket handler to use
   * @param cfg
   *   The configured [[AppCfg]]
   * @return
   *   Route that validates and handles websocket connections
   */
  private[this] def validateAndHandleWebSocket(
      args: SocketArgs
  )(
      webSocketHandler: => Route
  )(
      implicit cfg: AppCfg
  ): Route = {
    val topic = args.topic
    if (checkTopicExists(topic)) webSocketHandler
    else reject(ValidationRejection(s"Topic ${topic.value} does not exist"))
  }

  private[this] def extractKafkaCreds(
      authRes: WsProxyAuthResult,
      kafkaAuthHeader: Option[XKafkaAuthHeader]
  )(implicit cfg: AppCfg): Option[AclCredentials] = {
    cfg.server.openidConnect
      .map { oidcfg =>
        if (oidcfg.isKafkaTokenAuthOnlyEnabled) {
          log.trace("Only allowing Kafka auth through JWT token.")
          authRes.aclCredentials
        } else {
          log.trace(
            s"Allowing Kafka auth through JWT token or the" +
              s" ${Headers.KafkaAuthHeaderName} header."
          )
          // Always prefer the JWT token
          authRes.aclCredentials.orElse(kafkaAuthHeader.map(_.aclCredentials))
        }
      }
      .getOrElse {
        log.trace(
          "OpenID Connect is not configured. Using" +
            s" ${Headers.KafkaAuthHeaderName} header."
        )
        kafkaAuthHeader.map(_.aclCredentials)
      }
  }

  /**
   * @param inbound
   *   function defining the [[Route]] for the producer socket
   * @param outbound
   *   function defining the [[Route]] for the consumer socket
   * @param cfg
   *   Implicitly provided [[AppCfg]]
   * @param maybeOpenIdClient
   *   Implicitly provided Option that contains an [[OpenIdClient]] if OIDC is
   *   enabled.
   * @return
   *   The [[Route]] definition for the websocket endpoints
   */
  def websocketRoutes(
      inbound: InSocketArgs => Route,
      outbound: OutSocketArgs => Route
  )(
      implicit cfg: AppCfg,
      maybeOpenIdClient: Option[OpenIdClient]
  ): Route = {
    extractMaterializer { implicit mat =>
      maybeAuthenticate(cfg, maybeOpenIdClient, mat) { authResult =>
        pathPrefix("socket") {
          path("in") {
            optionalHeaderValueByType(XKafkaAuthHeader) { headerCreds =>
              val creds = extractKafkaCreds(authResult, headerCreds)
              inParams(cfg) { inArgs =>
                val args = inArgs
                  .withAclCredentials(creds)
                  .withBearerToken(authResult.maybeBearerToken)

                validateAndHandleWebSocket(args)(inbound(args))
              }
            }
          } ~ path("out") {
            optionalHeaderValueByType(XKafkaAuthHeader) { headerCreds =>
              val creds = extractKafkaCreds(authResult, headerCreds)
              outParams { outArgs =>
                val args = outArgs
                  .withAclCredentials(creds)
                  .withBearerToken(authResult.maybeBearerToken)
                validateAndHandleWebSocket(args)(outbound(args))
              }
            }
          }
        }
      }
    }
  }

}
