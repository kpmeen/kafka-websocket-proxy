package net.scalytica.kafka.wsproxy.errors

import scala.util.control.NoStackTrace

case class ConfigurationError(message: String) extends RuntimeException(message)

abstract class ProxyError(
    msg: String,
    cause: Option[Throwable] = None
) extends Exception(msg, cause.orNull)

case class OpenIdConnectError(message: String)
    extends ProxyError(message)
    with NoStackTrace

case class TopicNotFoundError(message: String)
    extends ProxyError(message)
    with NoStackTrace

case class IllegalFormatTypeError(message: String) extends ProxyError(message)

abstract class ProxyAuthError(msg: String, cause: Option[Throwable] = None)
    extends ProxyError(msg, cause)

case class AuthenticationError(message: String, cause: Option[Throwable] = None)
    extends ProxyAuthError(message, cause)
    with NoStackTrace

case class AuthorisationError(message: String, cause: Option[Throwable] = None)
    extends ProxyAuthError(message, cause)
    with NoStackTrace

sealed abstract class JwkError(msg: String, throwable: Option[Throwable])
    extends ProxyAuthError(msg, throwable)

case class InvalidPublicKeyError(
    message: String,
    cause: Option[Throwable] = None
) extends JwkError(message, cause)

case class SigningKeyNotFoundError(
    message: String,
    cause: Option[Throwable] = None
) extends JwkError(message, cause)
