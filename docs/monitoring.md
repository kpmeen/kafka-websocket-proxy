---
id: monitoring
title: Monitoring
slug: /monitoring
---


The `kafka-websocket-proxy` is built around libraries that themselves expose
telemetry and monitoring data via JMX. The most important ones are [pekko-http](https://pekko.apache.org/docs/pekko-http/current//server-side/websocket-support.html)
and [kafka-clients](https://kafka.apache.org/documentation/#monitoring).

In addition to these the `kafka-websocket-proxy` exposes relevant metrics for
some of the inner workings.

## Enable JMX

Enabling remote JMX connections for the `kafka-websocket-proxy` can be done by
starting the application with the correct JVM arguments.

**Example:**
The below arguments will expose JMX insecurely on port `11111` and only accept
connections from the same host as the application is running on.

```
-Dcom.sun.management.jmxremote
-Dcom.sun.management.jmxremote.authenticate=false
-Dcom.sun.management.jmxremote.ssl=false
-Dcom.sun.management.jmxremote.port=11111
-Dcom.sun.management.jmxremote.local.only=tru
```

### Configuring JMX

See [Server Configuration](configuration.md#server-configuration) for details on
how to configure the JMX related application properties.

## Enable Jolokia

To enable [Jolokia](https://jolokia.org) it is necessary to configure a
`javaagent` with the jolokia-jvm-agent jar file, and the desired configuration.

**Example:**
The below example will start and expose a Jolokia server on port `7777` on `localhost`.

```
-javaagent:/path/to/kafka-websocket-proxy/ext-libs/jolokia-jvm-agent.jar=port=7777,host=localhost
```

## Enable Prometheus JMX exporter

To enable the [Prometheus JMX exporter](https://github.com/prometheus/jmx_exporter)
it is necessary to configure a `javaagent` with the jolokia-jvm-agent jar file,
and the desired configuration.

**Example:**
The below will start and expose a Prometheus exporter server on port `9999`
with a config file. Making the metrics endpoint available at
`http://localhost:9999/metrics`.

```
-javaagent:/path/to/kafka-websocket-proxy/ext-libs/jmx_prometheus_javaagent.jar=9999:/path/to/kafka-websocket-proxy/ext-libs/prometheus-config.yaml
```

## Enable Jolokia in the pre-built Docker image

By default Jolokia is _not_ enabled in the pre-built docker image. To enable
Jolokia there are two environment variables that can be altered.

| Env            | Default                    | Description               |
|:-------        |:-----                      |:----------                |
| ENABLE_JOLOKIA | `false`                    | Set to `true` to enable. |
| JOLOKIA_CONFIG | `port=7777,host=localhost` | This is the place to add custom Jolokia configs. |


## Enable Prometheus JMX exporter in the pre-built Docker image

By default the Prometheus JMX exporter is _not_ enabled in the pre-built docker
image. To enable it there are three environment variables that can be altered.

| Env                        | Default                                 | Description               |
|:-------                    |:-----                                   |:----------                |
| ENABLE_PROMETHEUS_EXPORTER | `false`                                 | Set to `true` to enable. |
| PROMETHEUS_EXPORTER_PORT   | `7778`                                  | Set the port where the `/metrics` endpoint will be exposed. |
| PROMETHEUS_EXPORTER_CONFIG | `/opt/docker/ext/prometheus-config.yml` | Specify the prometheus config file to use. Remember to ensure the file is available from within the container. |

The default `prometheus-config.yml` is as follows:

```yml
---

startDelaySeconds: 0
lowercaseOutputName: false
lowercaseOutputLabelNames: false
```

## JMX metrics

| Metric                           | MBean name                                                                                                  | Description                                                                      |
|:-------                          |:---------------------                                                                                       |:-----                                                                            |
| **ProxyStatusMXBean**            |                                                                                                             |                                                                                  |
| Broker info list                 | net.scalytica.kafka.wsproxy:name=wsproxy-status,type=ProxyStatusMXBean                                      | For each broker contains `id`, `host`, `port`, `rack`                            |
| Http enabled                     | net.scalytica.kafka.wsproxy:name=wsproxy-status,type=ProxyStatusMXBean                                      | `true` or `false`                                                                |
| Https enabled                    | net.scalytica.kafka.wsproxy:name=wsproxy-status,type=ProxyStatusMXBean                                      | `true` or `false`                                                                |
| Basic Auth enabled               | net.scalytica.kafka.wsproxy:name=wsproxy-status,type=ProxyStatusMXBean                                      | `true` or `false`                                                                |
| OpenID Connect enabled           | net.scalytica.kafka.wsproxy:name=wsproxy-status,type=ProxyStatusMXBean                                      | `true` or `false`                                                                |
| Http port                        | net.scalytica.kafka.wsproxy:name=wsproxy-status,type=ProxyStatusMXBean                                      | The port number where HTTP is exposed                                            |
| Https port                       | net.scalytica.kafka.wsproxy:name=wsproxy-status,type=ProxyStatusMXBean                                      | The port number where HTTPS is exposed                                           |
| Session-state topic name         | net.scalytica.kafka.wsproxy:name=wsproxy-status,type=ProxyStatusMXBean                                      | Value of the configured session state topic name                                 |
| Up since                         | net.scalytica.kafka.wsproxy:name=wsproxy-status,type=ProxyStatusMXBean                                      | Date and time stamp when the proxy was last started                              |
| Uptime millis                    | net.scalytica.kafka.wsproxy:name=wsproxy-status,type=ProxyStatusMXBean                                      | Duration of uptime since last started                                            |
| **ConnectionsStatsMXBean**       |                                                                                                             |                                                                                  |
| Open WebSockets Total            | net.scalytica.kafka.wsproxy:name=wsproxy-connections,type=ConnectionsStatsMXBean                            | Total number of open WebSockets                                                  |
| Open WebSockets Producers        | net.scalytica.kafka.wsproxy:name=wsproxy-connections,type=ConnectionsStatsMXBean                            | Total number of open producer WebSockets                                         |
| Open WebSockets Consumers        | net.scalytica.kafka.wsproxy:name=wsproxy-connections,type=ConnectionsStatsMXBean                            | Total number of open consumer WebSockets                                         |
| **ConsumerClientStatsMXBean**    |                                                                                                             |                                                                                  |
| **global**                       |                                                                                                             |                                                                                  |
| Client Id                        | net.scalytica.kafka.wsproxy:name=wsproxy-consumer-stats-all-total,type=ConsumerClientStatsMXBean            | Value is `total` to show bean contains global stats across all consumers.        |
| Group Id                         | net.scalytica.kafka.wsproxy:name=wsproxy-consumer-stats-all-total,type=ConsumerClientStatsMXBean            | Value is `all` to show bean contains global stats across all consumers.          |
| Num records sent total           | net.scalytica.kafka.wsproxy:name=wsproxy-consumer-stats-all-total,type=ConsumerClientStatsMXBean            | The total number of records sent from the proxy instance.                        |
| Num records sent last hour       | net.scalytica.kafka.wsproxy:name=wsproxy-consumer-stats-all-total,type=ConsumerClientStatsMXBean            | The total number of records sent from the proxy instance in the last hour.       |
| Num records sent last minute     | net.scalytica.kafka.wsproxy:name=wsproxy-consumer-stats-all-total,type=ConsumerClientStatsMXBean            | The total number of records sent from the proxy instance in the last minute.     |
| Num commits received total       | net.scalytica.kafka.wsproxy:name=wsproxy-consumer-stats-all-total,type=ConsumerClientStatsMXBean            | The total number of commits received from the proxy instance.                    |
| Num commits received last hour   | net.scalytica.kafka.wsproxy:name=wsproxy-consumer-stats-all-total,type=ConsumerClientStatsMXBean            | The total number of commits received from the proxy instance in the last hour.   |
| Num commits received last minute | net.scalytica.kafka.wsproxy:name=wsproxy-consumer-stats-all-total,type=ConsumerClientStatsMXBean            | The total number of commits received from the proxy instance in the last minute. |
| **per client**                   |                                                                                                             |                                                                                  |
| Client Id                        | net.scalytica.kafka.wsproxy:name=wsproxy-consumer-stats-{groupId}-{clientId},type=ConsumerClientStatsMXBean | The client ID for a specific consumer.                                           |
| Group Id                         | net.scalytica.kafka.wsproxy:name=wsproxy-consumer-stats-{groupId}-{clientId},type=ConsumerClientStatsMXBean | The group ID for a specific consumer.                                            |
| Num records sent total           | net.scalytica.kafka.wsproxy:name=wsproxy-consumer-stats-{groupId}-{clientId},type=ConsumerClientStatsMXBean | The total number of records sent to the given client.                            |
| Num records sent last hour       | net.scalytica.kafka.wsproxy:name=wsproxy-consumer-stats-{groupId}-{clientId},type=ConsumerClientStatsMXBean | The total number of records sent to the given client in the last hour.           |
| Num records sent last minute     | net.scalytica.kafka.wsproxy:name=wsproxy-consumer-stats-{groupId}-{clientId},type=ConsumerClientStatsMXBean | The total number of records sent to the given client in the last minute.         |
| Num commits received total       | net.scalytica.kafka.wsproxy:name=wsproxy-consumer-stats-{groupId}-{clientId},type=ConsumerClientStatsMXBean | The total number of commits received from the given client.                      |
| Num commits received last hour   | net.scalytica.kafka.wsproxy:name=wsproxy-consumer-stats-{groupId}-{clientId},type=ConsumerClientStatsMXBean | The total number of commits received from the given client in the last hour.     |
| Num commits received last minute | net.scalytica.kafka.wsproxy:name=wsproxy-consumer-stats-{groupId}-{clientId},type=ConsumerClientStatsMXBean | The total number of commits received from the given client in the last minute.   |
| **ProducerClientStatsMXBean**    |                                                                                                             |                                                                                  |
| **global**                       |                                                                                                             |                                                                                  |
| Client Id                        | net.scalytica.kafka.wsproxy:name=wsproxy-producer-stats-all,type=ProducerClientStatsMXBean                  | Value is `all` to show bean contains global stats across all producers.          |
| Num records received total       | net.scalytica.kafka.wsproxy:name=wsproxy-producer-stats-all,type=ProducerClientStatsMXBean                  | The total number of records received from the proxy instance.                    |
| Num records received last hour   | net.scalytica.kafka.wsproxy:name=wsproxy-producer-stats-all,type=ProducerClientStatsMXBean                  | The total number of records received from the proxy instance in the last hour.   |
| Num records received last minute | net.scalytica.kafka.wsproxy:name=wsproxy-producer-stats-all,type=ProducerClientStatsMXBean                  | The total number of records received from the proxy instance in the last minute. |
| Num acks sent total              | net.scalytica.kafka.wsproxy:name=wsproxy-producer-stats-all,type=ProducerClientStatsMXBean                  | The total number of acks sent from the proxy instance.                           |
| Num acks sent last hour          | net.scalytica.kafka.wsproxy:name=wsproxy-producer-stats-all,type=ProducerClientStatsMXBean                  | The total number of acks sent from the proxy instance in the last hour.          |
| Num acks sent last minute        | net.scalytica.kafka.wsproxy:name=wsproxy-producer-stats-all,type=ProducerClientStatsMXBean                  | The total number of acks sent from the proxy instance in the last minute.        |
| **per client**                   |                                                                                                             |                                                                                  |
| Client Id                        | net.scalytica.kafka.wsproxy:name=wsproxy-producer-stats-{clientId},type=ProducerClientStatsMXBean           | The client ID for a specific producer.                                           |
| Num records received total       | net.scalytica.kafka.wsproxy:name=wsproxy-producer-stats-{clientId},type=ProducerClientStatsMXBean           | The total number of records received from the given client.                      |
| Num records received last hour   | net.scalytica.kafka.wsproxy:name=wsproxy-producer-stats-{clientId},type=ProducerClientStatsMXBean           | The total number of records received from the given client in the last hour.     |
| Num records received last minute | net.scalytica.kafka.wsproxy:name=wsproxy-producer-stats-{clientId},type=ProducerClientStatsMXBean           | The total number of records received from the given client in the last minute.   |
| Num acks sent total              | net.scalytica.kafka.wsproxy:name=wsproxy-producer-stats-{clientId},type=ProducerClientStatsMXBean           | The total number of acks sent from the given client.                             |
| Num acks sent last hour          | net.scalytica.kafka.wsproxy:name=wsproxy-producer-stats-{clientId},type=ProducerClientStatsMXBean           | The total number of acks sent from the given client in the last hour.            |
| Num acks sent last minute        | net.scalytica.kafka.wsproxy:name=wsproxy-producer-stats-{clientId},type=ProducerClientStatsMXBean           | The total number of acks sent from the given client in the last minute.          |
