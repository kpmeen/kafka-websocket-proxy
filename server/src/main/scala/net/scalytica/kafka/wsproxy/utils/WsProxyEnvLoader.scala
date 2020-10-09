package net.scalytica.kafka.wsproxy.utils

object WsProxyEnvLoader {

  def keys: Set[String] = properties.keySet

  def hasKey(key: String): Boolean = keys.exists(_.equals(key))

  def properties: Map[String, String] = sys.env ++ sys.props

  def get(key: String): Option[String] = properties.get(key)

  def exists(key: String): Boolean =
    sys.env.exists(_._1.equals(key)) || sys.props.exists(_._1.equals(key))

  def envString(keyPrefix: String*): String =
    properties.view
      .filterKeys(k => keyPrefix.exists(k.startsWith))
      .toMap
      .mkString("\n")
}
