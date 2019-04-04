package net.scalytica.kafka.wsproxy.models

import Formats.FormatType

object ValueDetails {

  case class InValueDetails[T](
      value: T,
      format: FormatType,
      schema: Option[String] = None
  )

  case class OutValueDetails[T](
      value: T,
      format: Option[FormatType] = None
  )
}
