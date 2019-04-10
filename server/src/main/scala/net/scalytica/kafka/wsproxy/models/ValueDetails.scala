package net.scalytica.kafka.wsproxy.models

import Formats.FormatType

object ValueDetails {

  case class InValueDetails[T](value: T, format: FormatType)

  case class OutValueDetails[T](value: T, format: Option[FormatType] = None)

}
