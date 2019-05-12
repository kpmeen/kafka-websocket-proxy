package net.scalytica.kafka.wsproxy

package object codecs {

  implicit private[codecs] val cfg: io.circe.generic.extras.Configuration =
    io.circe.generic.extras.Configuration.default

}
