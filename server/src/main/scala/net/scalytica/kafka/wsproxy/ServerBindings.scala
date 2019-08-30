package net.scalytica.kafka.wsproxy

import java.nio.file.{Files, Path}
import java.security.{KeyStore, SecureRandom}

import akka.actor.ActorSystem
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.{ConnectionContext, Http, HttpsConnectionContext}
import akka.stream.Materializer
import javax.net.ssl.{KeyManagerFactory, SSLContext, TrustManagerFactory}
import net.scalytica.kafka.wsproxy.Configuration.{AppCfg, ServerSslCfg}

import scala.concurrent.Future

trait ServerBindings {

  def initialisePlainBinding(
      implicit
      cfg: AppCfg,
      routes: Route,
      sys: ActorSystem,
      mat: Materializer
  ): Option[Future[Http.ServerBinding]] = {
    if (cfg.server.ssl.exists(_.sslOnly.equals(true))) None
    else
      Option(
        Http().bindAndHandle(
          handler = routes,
          interface = cfg.server.bindInterface,
          port = cfg.server.port
        )
      )
  }

  // scalastyle:off
  def initialiseSslBinding(
      implicit
      cfg: AppCfg,
      routes: Route,
      sys: ActorSystem,
      mat: Materializer
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

        Http().bindAndHandle(
          handler = routes,
          interface = sslCfg.bindInterface.getOrElse(cfg.server.bindInterface),
          port = port, // scalastyle:ignore
          connectionContext = httpsCtx
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
    ConnectionContext.https(
      sslContext = ctx,
      sslConfig = None,
      enabledCipherSuites = None,
      enabledProtocols = None,
      clientAuth = None,
      sslParameters = None
    )
  }
}
