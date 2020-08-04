package net.scalytica.test

import com.sksamuel.avro4s._
import net.scalytica.kafka.wsproxy.codecs.WsProxyAvroSerde
import org.apache.kafka.common.serialization.{Serde, Serdes}

trait TestTypes {

  implicit lazy val testKeySchemaFor  = TestKey.schemaFor
  implicit lazy val testKeyToRecord   = TestKey.toRecord
  implicit lazy val testKeyFromRecord = TestKey.fromRecord

  implicit lazy val albumSchemaFor  = Album.schemaFor
  implicit lazy val albumToRecord   = Album.toRecord
  implicit lazy val albumFromRecord = Album.fromRecord

  object TestSerdes {

    val stringSerdes: Serde[String] = Serdes.String()
    val longSerdes: Serde[Long]     = Serdes.Long().asInstanceOf[Serde[Long]]

    val keySerdes: WsProxyAvroSerde[TestKey] = {
      val srCfg = registryConfig()(None)
      WsProxyAvroSerde[TestKey](srCfg, isKey = true)
    }

    val valueSerdes: WsProxyAvroSerde[Album] = {
      val srCfg = registryConfig()(None)
      WsProxyAvroSerde[Album](srCfg, isKey = false)
    }
  }

  case class TestKey(
      username: String,
      timestamp: Long
  )

  object TestKey {
    lazy val schemaFor  = SchemaFor[TestKey]
    lazy val toRecord   = ToRecord[TestKey]
    lazy val fromRecord = FromRecord[TestKey]

    lazy val schema = AvroSchema[TestKey]
  }

  case class Album(
      artist: String,
      title: String,
      tracks: Seq[Track]
  )

  object Album {
    lazy val schemaFor  = SchemaFor[Album]
    lazy val toRecord   = ToRecord[Album]
    lazy val fromRecord = FromRecord[Album]

    lazy val schema = AvroSchema[Album]
  }

  case class Track(
      name: String,
      duration: Long
  )
}

object TestTypes extends TestTypes
