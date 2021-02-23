package net.scalytica.test

import akka.actor.ActorSystem
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.Materializer
import io.circe.JsonObject
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.auto._
import io.circe.syntax._
import net.scalytica.kafka.wsproxy.auth.{
  AccessToken,
  Jwk,
  OpenIdClient,
  OpenIdConnectConfig,
  PubKeyAlgo
}
import net.scalytica.kafka.wsproxy.config.Configuration.{
  CustomJwtCfg,
  OpenIdConnectCfg
}
import org.scalatest.OptionValues
import org.scalatest.concurrent.ScalaFutures
import pdi.jwt._

import java.security.KeyPairGenerator
import java.security.interfaces.{RSAPrivateKey, RSAPublicKey}
import java.util.{Base64, UUID}
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

trait MockOpenIdServer
    extends EmbeddedHttpServer
    with ScalaFutures
    with OptionValues {

  implicit val openIdCirceConfig: Configuration =
    Configuration.default.withSnakeCaseMemberNames

  val oidClientSecret =
    "qqnBeq9_2X2NsNzVvCzmPTmtkjHWZFz3vr4gzSg9y6B_NjytxoRO_HsoPnTmF8s4"
  val oidClientId = "GIczqrC3HUhnZNw67dTM5Q5977sOR9Gp"
  val oidAudience = "http://kptest.scalytica.net"
  val oidGrantTpe = "client_credentials"

  val jwtKafkaCredsUsernameKey = "net.scalytica.jwt.username"
  val jwtKafkaCredsPasswordKey = "net.scalytica.jwt.password"

  val customJwtCfg =
    CustomJwtCfg(jwtKafkaCredsUsernameKey, jwtKafkaCredsPasswordKey)

  val keyAlgorithm = PubKeyAlgo
  // Generate RSA private/public key pair for mocking OIDC
  private[this] val kpg = KeyPairGenerator.getInstance(keyAlgorithm)
  kpg.initialize(2048) // scalastyle:ignore

  private[this] val keyPair = kpg.generateKeyPair()

  private[this] def base64UrlEncode(bytes: Array[Byte]): String =
    JwtBase64.encodeString(bytes)

  private[this] def base64Encode(bytes: Array[Byte]): String =
    Base64.getEncoder.encodeToString(bytes)

  val rsaPrivateKey = keyPair.getPrivate.asInstanceOf[RSAPrivateKey]
  val rsaPubKey     = keyPair.getPublic.asInstanceOf[RSAPublicKey]

  val rsaPrivateKeyString: String = base64Encode(rsaPrivateKey.getEncoded)
  val rsaPublicKeyString: String  = base64Encode(rsaPubKey.getEncoded)

  val keyId            = UUID.randomUUID().toString
  val issuedAtMillis   = System.currentTimeMillis()
  val expirationMillis = (60 seconds).toMillis + issuedAtMillis

  val jwkKey = Jwk(
    kty = keyAlgorithm,
    kid = Option(keyId),
    alg = Option(JwtAlgorithm.RS256.name),
    use = Option("sig"),
    key_ops = None,
    x5u = None,
    x5c = None,
    x5t = None,
    n = Option(base64UrlEncode(rsaPubKey.getModulus.toByteArray)),
    e = Option(base64UrlEncode(rsaPubKey.getPublicExponent.toByteArray))
  )

  val jwtHeader = JwtHeader(JwtAlgorithm.RS256).withType("JWT").withKeyId(keyId)

  val jwtDataContentJson =
    """{
      |  "azp": "GIczqrC3HUhnZNw67dTM5Q5977sOR9Gp",
      |  "gty": "client-credentials"
      |}""".stripMargin

  val jwtKafkaUsernameJson = s""""$jwtKafkaCredsUsernameKey":"client""""
  val jwtKafkaPasswordJson = s""""$jwtKafkaCredsPasswordKey":"client""""

  val jwtKafkaCredsJson =
    s"""{
      |  $jwtKafkaUsernameJson,
      |  $jwtKafkaPasswordJson
      |}""".stripMargin

  val supportedResponseTypes = List(
    "code",
    "token",
    "id_token",
    "code token",
    "code id_token",
    "token id_token",
    "code token id_token"
  )
  val subjectTypes         = List("public")
  val supportedSigningAlgs = List("HS256", "RS256")

  def openIdConnectConfig(
      host: String,
      port: Int
  ): OpenIdConnectConfig = {
    val baseUrl = s"http://$host:$port"
    OpenIdConnectConfig(
      issuer = baseUrl,
      jwksUri = s"$baseUrl/oauth/.well-known/jwks.json",
      authorizationEndpoint = s"$baseUrl/oauth/authorize",
      tokenEndpoint = s"$baseUrl/oauth/token",
      responseTypesSupported = supportedResponseTypes,
      subjectTypesSupported = subjectTypes,
      idTokenSigningAlgValuesSupported = supportedSigningAlgs
    )
  }

  def jwtData(
      issuerUrl: String,
      expiration: Long,
      issuedAt: Long,
      useJwtKafkaCreds: Boolean
  ): JwtClaim = {
    val claim = JwtClaim(
      content = jwtDataContentJson,
      issuer = Option(issuerUrl),
      subject = Option("GIczqrC3HUhnZNw67dTM5Q5977sOR9Gp@clients"),
      audience = Option(Set(oidAudience)),
      expiration = Option(expiration),
      issuedAt = Option(issuedAt)
    )
    if (useJwtKafkaCreds) claim + jwtKafkaCredsJson
    else claim
  }

  def generateJwt(jwtClaim: JwtClaim): String = {
    Jwt.encode(
      header = jwtHeader.toJson,
      claim = jwtClaim.toJson,
      key = rsaPrivateKeyString,
      algorithm = JwtAlgorithm.RS256
    )
  }

  def accessToken(
      host: String,
      port: Int,
      useJwtKafkaCreds: Boolean
  ): AccessToken = {
    val jd = jwtData(
      issuerUrl = s"http://$host:$port",
      expiration = expirationMillis,
      issuedAt = issuedAtMillis,
      useJwtKafkaCreds = useJwtKafkaCreds
    )
    val token = generateJwt(jd)
    AccessToken(
      tokenType = "Bearer",
      accessToken = token,
      expiresIn = expirationMillis,
      refreshToken = None
    )
  }

  def tokenRoute(host: String, port: Int, useJwtKafkaCreds: Boolean): Route = {
    val body = accessToken(host, port, useJwtKafkaCreds).asJson.spaces2
    path("token") {
      post {
        complete(
          HttpEntity(
            contentType = ContentTypes.`application/json`,
            string = body
          )
        )
      }
    }
  }

  def wellKnownOpenIdUrl(host: String, port: Int): Route =
    path("openid-connect") {
      get {
        complete(
          HttpEntity(
            contentType = ContentTypes.`application/json`,
            string = openIdConnectConfig(host, port).asJson.spaces2
          )
        )
      }
    }

  def wellKnownJwkUrl: Route = {
    path("jwks.json") {
      get {
        complete(
          HttpEntity(
            contentType = ContentTypes.`application/json`,
            string = JsonObject("keys" -> Seq(jwkKey).asJson).asJson.spaces2
          )
        )
      }
    }
  }

  def oauthRoutes(host: String, port: Int, useJwtKafkaCreds: Boolean): Route =
    pathPrefix("oauth") {
      tokenRoute(host, port, useJwtKafkaCreds) ~ pathPrefix(".well-known") {
        wellKnownOpenIdUrl(host, port) ~ wellKnownJwkUrl
      }
    }

  def wellKnownOpenIdUrlString(host: String, port: Int): String =
    s"http://$host:$port/oauth/.well-known/openid-connect"

  def withOpenIdConnectServer[T](
      host: String = "localhost",
      port: Int = availablePort,
      useJwtKafkaCreds: Boolean
  )(block: (String, Int, OpenIdConnectCfg) => T)(
      implicit sys: ActorSystem,
      ec: ExecutionContext
  ): T = {
    withHttpServerForRoute(host, port)((h, p) =>
      oauthRoutes(h, p, useJwtKafkaCreds)
    ) { case (h, p) =>
      val cfg = OpenIdConnectCfg(
        wellKnownUrl = Option(wellKnownOpenIdUrlString(host, port)),
        audience = Option(oidAudience),
        realm = None,
        enabled = true,
        requireHttps = false,
        customJwt =
          if (useJwtKafkaCreds) Some(customJwtCfg)
          else None
      )
      block(h, p, cfg)
    }
  }

  def withOpenIdConnectServerAndClient[T](
      host: String = "localhost",
      port: Int = availablePort,
      useJwtKafkaCreds: Boolean
  )(block: (String, Int, OpenIdClient, OpenIdConnectCfg) => T)(
      implicit sys: ActorSystem,
      mat: Materializer,
      ec: ExecutionContext
  ): T =
    withOpenIdConnectServer(host, port, useJwtKafkaCreds) { case (h, p, cfg) =>
      val client = OpenIdClient(
        oidcCfg = cfg,
        enforceHttps = false
      )
      block(h, p, client, cfg)
    }

  def withOpenIdConnectServerAndToken[T](
      host: String = "localhost",
      port: Int = availablePort,
      useJwtKafkaCreds: Boolean
  )(block: (String, Int, OpenIdClient, OpenIdConnectCfg, AccessToken) => T)(
      implicit sys: ActorSystem,
      mat: Materializer,
      ec: ExecutionContext
  ): T =
    withOpenIdConnectServerAndClient(host, port, useJwtKafkaCreds) {
      case (h, p, client, cfg) =>
        lazy val token = client
          .generateToken(oidClientId, oidClientSecret, oidAudience, oidGrantTpe)
          .futureValue
          .value
        block(h, p, client, cfg, token)
    }

  def withUnavailableOpenIdConnectServerAndToken[T](
      host: String = "localhost",
      port: Int = availablePort,
      useJwtKafkaCreds: Boolean
  )(block: (OpenIdClient, OpenIdConnectCfg, AccessToken) => T)(
      implicit mat: Materializer
  ): T = {
    val cfg = OpenIdConnectCfg(
      wellKnownUrl = Option(wellKnownOpenIdUrlString(host, port)),
      audience = Option(oidAudience),
      realm = None,
      enabled = true,
      requireHttps = false,
      customJwt =
        if (useJwtKafkaCreds) Some(customJwtCfg)
        else None
    )
    lazy val client = OpenIdClient(
      oidcCfg = cfg,
      enforceHttps = false
    )
    val token = accessToken(host, port, useJwtKafkaCreds)
    block(client, cfg, token)
  }

}
