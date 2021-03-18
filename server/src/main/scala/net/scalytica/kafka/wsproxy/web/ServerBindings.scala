package net.scalytica.kafka.wsproxy.web

import akka.actor.ActorSystem
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.{ConnectionContext, Http, HttpsConnectionContext}
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

  def initialisePlainBinding(
      implicit cfg: AppCfg,
      routes: Route,
      sys: ActorSystem
  ): Option[Future[Http.ServerBinding]] = {
    if (cfg.server.ssl.exists(_.sslOnly)) None
    else {
      if (!cfg.server.isAuthSecurelyEnabled) {
        // scalastyle:off
        println(
          s"""
            |-------------------------------------------------------------------
            |  WARNING:
            |-------------------------------------------------------------------
            |Server starting with authentication and non-TLS binding
            |
            |interface: ${cfg.server.bindInterface}
            |     port: ${cfg.server.port}
            |
            |This will allow credentials to be transmitted in plain text over
            |the network, and could compromise security!
            |-------------------------------------------------------------------
            |""".stripMargin
        )
        // scalastyle:on
      }
      Option(
        initialiseBinding(cfg.server.bindInterface, cfg.server.port, routes)
      )
    }
  }

  // scalastyle:off
  def initialiseSslBinding(
      implicit cfg: AppCfg,
      routes: Route,
      sys: ActorSystem
  ): Option[Future[Http.ServerBinding]] = {
    cfg.server.ssl.flatMap { sslCfg =>
      val ks: KeyStore = KeyStore.getInstance("PKCS12")

      sslCfg.port.map { port =>
        val keystore = sslCfg.keystoreLocation
          .map(s => Path.of(s))
          .map(path => Files.newInputStream(path))

        require(
          requirement = keystore.nonEmpty,
          message = "SSL is configured but no keystore could be found"
        )

        keystore.foreach(store => ks.load(store, sslCfg.liftKeystorePassword))

        val sslCtx   = initSslContext(ks, sslCfg)
        val httpsCtx = httpsContext(sslCtx)

        initialiseBinding(
          interface = sslCfg.bindInterface.getOrElse(cfg.server.bindInterface),
          port = port,
          routes = routes,
          httpsCtx = Some(httpsCtx)
        )
      }
    }
  }

  private[this] def initSslContext(
      keyStore: KeyStore,
      sslCfg: ServerSslCfg
  ): SSLContext = {
    val kmf: KeyManagerFactory   = KeyManagerFactory.getInstance("SunX509")
    val tmf: TrustManagerFactory = TrustManagerFactory.getInstance("SunX509")
    val sslCtx: SSLContext       = SSLContext.getInstance("TLS")

    kmf.init(keyStore, sslCfg.liftKeystorePassword)
    tmf.init(keyStore)
    sslCtx.init(kmf.getKeyManagers, tmf.getTrustManagers, new SecureRandom)

    sslCtx
  }

  private[this] def httpsContext(ctx: SSLContext): HttpsConnectionContext = {
    ConnectionContext.httpsServer(sslContext = ctx)
  }
}
