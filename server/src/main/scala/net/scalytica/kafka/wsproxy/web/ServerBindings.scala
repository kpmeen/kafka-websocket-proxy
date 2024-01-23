package net.scalytica.kafka.wsproxy.web

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.{
  ConnectionContext,
  Http,
  HttpsConnectionContext
}
import net.scalytica.kafka.wsproxy.config.Configuration.{AppCfg, ServerSslCfg}

import java.nio.file.{Files, Path}
import java.security.{KeyStore, SecureRandom}
import javax.net.ssl.{KeyManagerFactory, SSLContext, TrustManagerFactory}
import scala.concurrent.Future

trait ServerBindings {

  private[this] def initialiseBinding(
      interface: String,
      port: Int,
      routes: Route,
      httpsCtx: Option[HttpsConnectionContext] = None
  )(implicit sys: ActorSystem): Future[Http.ServerBinding] = {
    val sb = Http().newServerAt(
      interface = interface,
      port = port
    )
    httpsCtx.map(sb.enableHttps).getOrElse(sb).bindFlow(routes)
  }

  private[this] def printUnsecuredWarning()(implicit cfg: AppCfg): Unit = {
    val srv = cfg.server
    val adm = srv.admin

    def middle(name: String, iface: String, port: Int) = {
      s"""${name.toUpperCase}
         |----------------------------
         |interface: $iface
         |     port: $port
         |""".stripMargin
    }
    // scalastyle:off
    println(
      s"""
         |-------------------------------------------------------------------
         |  WARNING:
         |-------------------------------------------------------------------
         |Server starting with authentication and non-TLS binding
         |
         |${middle("proxy", srv.bindInterface, srv.port)}
         |${if (adm.enabled) middle("admin", adm.bindInterface, adm.port)}
         |This will allow credentials to be transmitted in plain text over
         |the network, and could compromise security!
         |-------------------------------------------------------------------
         |""".stripMargin
    )
    // scalastyle:on
  }

  def initialiseAdminBinding(routes: Route)(
      implicit cfg: AppCfg,
      sys: ActorSystem
  ): Option[Future[Http.ServerBinding]] = {
    val adminCfg = cfg.server.admin

    if (adminCfg.enabled) {
      if (cfg.server.isSslEnabled) {
        val httpsCtx = createHttpsContext()
        Option(
          initialiseBinding(
            interface = adminCfg.bindInterface,
            port = adminCfg.port,
            routes = routes,
            httpsCtx = httpsCtx
          )
        )
      } else {
        Option(initialiseBinding(adminCfg.bindInterface, adminCfg.port, routes))
      }
    } else None
  }

  def initialisePlainBinding(
      implicit cfg: AppCfg,
      routes: Route,
      sys: ActorSystem
  ): Option[Future[Http.ServerBinding]] = {
    if (cfg.server.ssl.exists(_.sslOnly)) None
    else {
      if (!cfg.server.isAuthSecurelyEnabled) {
        printUnsecuredWarning()
      }
      Option(
        initialiseBinding(cfg.server.bindInterface, cfg.server.port, routes)
      )
    }
  }

  private[this] lazy val keyStoreType: String = "PKCS12"
  private[this] lazy val keyStoreInstance = KeyStore.getInstance(keyStoreType)
  private[this] lazy val secFactoryType: String = "SunX509"
  private[this] lazy val sslContextType: String = "TLS"

  private[this] def createHttpsContext()(
      implicit cfg: AppCfg
  ): Option[HttpsConnectionContext] = {
    if (cfg.server.isSslEnabled) {
      cfg.server.ssl.map { sslCfg =>
        val keystore = sslCfg.keystoreLocation
          .map(s => Path.of(s))
          .map(path => Files.newInputStream(path))

        require(
          requirement = keystore.nonEmpty,
          message = "SSL is configured but no keystore could be found"
        )

        keystore.foreach { store =>
          keyStoreInstance.load(store, sslCfg.liftKeystorePassword)
        }

        val sslCtx = initSslContext(keyStoreInstance, sslCfg)
        httpsContext(sslCtx)
      }
    } else None
  }

  // scalastyle:off
  def initialiseSslBinding(
      implicit cfg: AppCfg,
      routes: Route,
      sys: ActorSystem
  ): Option[Future[Http.ServerBinding]] = {
    cfg.server.ssl.flatMap { sslCfg =>
      sslCfg.port.map { port =>
        val httpsCtx = createHttpsContext()

        initialiseBinding(
          interface = sslCfg.bindInterface.getOrElse(cfg.server.bindInterface),
          port = port,
          routes = routes,
          httpsCtx = httpsCtx
        )
      }
    }
  }

  private[this] def initSslContext(
      keyStore: KeyStore,
      sslCfg: ServerSslCfg
  ): SSLContext = {
    val kmf: KeyManagerFactory = KeyManagerFactory.getInstance(secFactoryType)
    val tmf: TrustManagerFactory =
      TrustManagerFactory.getInstance(secFactoryType)
    val sslCtx: SSLContext = SSLContext.getInstance(sslContextType)

    kmf.init(keyStore, sslCfg.liftKeystorePassword)
    tmf.init(keyStore)
    sslCtx.init(kmf.getKeyManagers, tmf.getTrustManagers, new SecureRandom)

    sslCtx
  }

  private[this] def httpsContext(ctx: SSLContext): HttpsConnectionContext = {
    ConnectionContext.httpsServer(sslContext = ctx)
  }
}
