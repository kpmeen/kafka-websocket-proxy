package net.scalytica.kafka.wsproxy

object Formats {

  sealed trait FormatType { self =>

    lazy val name: String =
      self.getClass.getSimpleName.stripSuffix("$").toLowerCase()

  }

  // Complex types
  case object Json     extends FormatType
  case object Avro     extends FormatType
  case object Protobuf extends FormatType

  // "Primitives"
  case object ByteArray extends FormatType
  case object String    extends FormatType
  case object Char      extends FormatType
  case object Int       extends FormatType
  case object Short     extends FormatType
  case object Long      extends FormatType
  case object Double    extends FormatType
  case object Float     extends FormatType

  object FormatType {

    val All = List(
      Json,
      Avro,
      Protobuf,
      ByteArray,
      String,
      Char,
      Int,
      Short,
      Long,
      Double,
      Float
    )

    // scalastyle:off cyclomatic.complexity
    def fromString(s: String): Option[FormatType] = s match {
      case str: String if str == Json.name      => Some(Json)
      case str: String if str == Avro.name      => Some(Avro)
      case str: String if str == ByteArray.name => Some(ByteArray)
      case str: String if str == String.name    => Some(String)
      case str: String if str == Char.name      => Some(Char)
      case str: String if str == Int.name       => Some(Int)
      case str: String if str == Short.name     => Some(Short)
      case str: String if str == Long.name      => Some(Long)
      case str: String if str == Double.name    => Some(Double)
      case str: String if str == Float.name     => Some(Float)
      case unsupported                          => None
    }
    // scalastyle:on cyclomatic.complexity

    def unsafeFromString(s: String): FormatType = fromString(s).getOrElse {
      throw new IllegalArgumentException(s"$s is not a valid format type")
    }

  }

}
