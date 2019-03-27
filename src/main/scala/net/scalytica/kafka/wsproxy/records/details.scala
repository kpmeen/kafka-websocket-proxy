package net.scalytica.kafka.wsproxy.records

import net.scalytica.kafka.wsproxy.Formats.FormatType

case class InValueDetails[T](
    value: T,
    format: FormatType,
    schema: Option[String] = None
)

case class OutValueDetails[T](
    value: T,
    format: Option[FormatType] = None
)
