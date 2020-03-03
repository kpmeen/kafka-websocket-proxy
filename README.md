[![pipeline status](https://gitlab.com/kpmeen/kafka-websocket-proxy/badges/master/pipeline.svg)](https://gitlab.com/kpmeen/kafka-websocket-proxy/commits/master)
[![coverage report](https://gitlab.com/kpmeen/kafka-websocket-proxy/badges/master/coverage.svg)](https://gitlab.com/kpmeen/kafka-websocket-proxy/commits/master)

# Kafka WebSocket Proxy

The Kafka WebSocket Proxy is mainly created as a more efficient alternative to
the existing HTTP based REST proxy.

With WebSockets, the overhead of the HTTP protocol is removed. Instead, a much
"cheaper" TCP socket is setup between the client and proxy, through any
intermediate networking components.

Since WebSockets are bidirectional. They open up for client - server
implementations that are much closer to the regular Kafka client. The WebSocket
becomes more like an extension of the proxy-internal consumer/producer streams. 

## Getting started


## Configuration

The Kafka WebSocket Proxy is built on Akka. More specifically:

* [akka-http](https://doc.akka.io/docs/akka-http/current/scala.html)
* [akka-streams](https://doc.akka.io/docs/akka/current/stream/index.html)
* [alpakka-kafka](https://doc.akka.io/docs/akka-stream-kafka/current/home.html)

All of these libraries/frameworks come with their own set of configuration
parameters and possibilities. In the Kafka WebSocket Proxy, these are kept in
separate configuration files to more easily find and adjust the configurations
for each of them.

The Kafka WebSocket Proxy itself is configured through the `application.conf`
file. Where the following parameters can be adjusted:

> NOTE:
> Some parameters are configurable through specific environment variables. 
> For a complete overview of all the configuration parameters, please refer to
> the `application.conf` file in `src/main/resources`.


### Main Server Configuration

Basic properties allowing configurations of things related to the basic server.
Allows for changing things like network interface, port number, etc. 
 
| Config key                                                      | Environment                              | Default                  | Description   |
|:---                                                             |:----                                     |:------------------------:|:-----         |
| kafka.ws.proxy.server.server-id                                 | WSPROXY_SERVER_ID                        | `node-1`                 | A unique identifier for the specific kafka-websocket-proxy instance. |
| kafka.ws.proxy.server.bind-interface                            | WSPROXY_BIND_INTERFACE                   | `0.0.0.0`                | Network interface to bind unsecured traffic to. |
| kafka.ws.proxy.server.port                                      | WSPROXY_PORT                             | `8078`                   | Port where the unsecured endpoints will be available. |
| kafka.ws.proxy.server.ssl.ssl-only                              | WSPROXY_SSL_ONLY                         | `false`                  | Indicates if the server should use SSL/TLS only binding when SSL/TLS is enabled. |
| kafka.ws.proxy.server.ssl.bind-interface                        | WSPROXY_SSL_BIND_INTERFACE               | `0.0.0.0`                | Network interface to bind the SSL/TLS traffic to.
| kafka.ws.proxy.server.ssl.port                                  | WSPROXY_SSL_PORT                         | not set                  | Port where the SSL/TLS endpoints will be available. |
| kafka.ws.proxy.server.ssl.keystore-location                     | WSPROXY_SSL_KEYSTORE_LOCATION            | not set                  | File path to location of key store file. |
| kafka.ws.proxy.server.ssl.keystore-password                     | WSPROXY_SSL_KEYSTORE_PASS                | not set                  | Password for the key store file. |


### Internal Session Handler

The `kafka-websocket-proxy` needs to keep some state about the different active
sessions across a multi-node deployment. The state is synced to other nodes
through a dedicated Kafka topic and kept up to date in each node in an in-memory
data structure. This allows e.g. controlling the number of open WebSockets in
a given consumer group, and much more. The below properties gives some
possibility to change the behaviour of the session handler.

| Config key                                                      | Environment                              | Default                  | Description   |
|:---                                                             |:----                                     |:------------------------:|:-----         |
| kafka.ws.proxy.session-handler.session-state-topic-name         | WSPROXY_SESSION_STATE_TOPIC              | `_wsproxy.session.state` | The name of the compacted topic where session state is kept. |
| kafka.ws.proxy.session-handler.session-state-replication-factor | WSPROXY_SESSION_STATE_REPLICATION_FACTOR | `3`                      | How many replicas to keep for the session state topic. |
| kafka.ws.proxy.session-handler.session-state-retention          | WSPROXY_SESSION_STATE_RETENTION          | `30 days`                | How long to keep sessions in the session state topic. |


### Internal Message Commit Handler

When a WebSocket client connects, it can specify whether or not the auto-commit
feature should be used. In the case where the client opens the connection with
`autoCommit=false` in the query parameters, the websocket will keep track of
the uncommitted message offsets in an in-memory "stack" structure in a _commit
handler_. This allows the client to send in a special _commit_ message on the
inbound WebSocket channel, that will trigger a given message offset to be
committed to Kafka.
The below properties allows to tune some of the parameters that affect the
behaviour of the commit handler 

| Config key                                         | Environment                    | Default      | Description   |
|:---                                                |:----                           |:------------:|:-----         |
| kafka.ws.proxy.commit-handler.max-stack-size       | WSPROXY_CH_MAX_STACK_SIZE      | `100`        | The maximum number of uncommitted messages, per partition, that will be kept track of in the commit handler stack. |
| kafka.ws.proxy.commit-handler.auto-commit-enabled  | WSPROXY_CH_AUTOCOMMIT_ENABLED  | `false`      | Whether or not to allow the proxy to perform automatic offset commits of uncommitted messages. |
| kafka.ws.proxy.commit-handler.auto-commit-interval | WSPROXY_CH_AUTOCOMMIT_INTERVAL | `1 second`   | The interval to execute the jobo for auto-committing messages of a given age. |
| kafka.ws.proxy.commit-handler.auto-commit-max-age  | WSPROXY_CH_AUTOCOMMIT_MAX_AGE  | `20 seconds` | The max allowed age of uncommitted messages in the commit handler stack. |


### Internal Kafka Client

Exposed configuration properties for the Kafka clients initialised and used by
the `kafka-websocket-proxy` whenever a WebSocket connection is established.  

| Config key                                        | Environment                       | Default | Description   |
|:---                                               |:----                              |:-------:|:-----         |
| kafka.ws.proxy.kafka-client.bootstrap-hosts       | WSPROXY_KAFKA_BOOTSTRAP_HOSTS     |         | A string with the Kafka brokers to bootstrap against, in the form `<host>:<port>`, separated by comma. |
| kafka.ws.proxy.kafka-client.schema-registry-url   | WSPROXY_SCHEMA_REGISTRY_URL       |         | URLs for the Confluent Schema Registry. |
| kafka.ws.proxy.kafka-client.auto-register-schemas | WSPROXY_SCHEMA_AUTO_REGISTER      | `true`  | By default, the proxy will automatically register any internal Avro schemas it needs. If disabled, these schemas must be registered with the schema registry manually. |
| kafka.ws.proxy.kafka-client.metrics-enabled       | WSPROXY_CONFLUENT_METRICS_ENABLED | `false` | When this flag is set to `true`, it will enable the Confluent Metrics Reporter |



### Kafka Security

The `kafka-websocket-proxy` allows setting Kafka client specific properties
under the key `kafka.ws.proxy.kafka-client.properties` in the `application.conf`
file. To connect to a secure Kafka cluster, the necessary security properties
should be added here. Below is a table containing the properties that are
currently possible to set using specific environment variables:
  

| Config key                                                                   | Environment                              | Default      |
|:---                                                                          |:----                                     |:------------:|
| kafka.ws.proxy.kafka-client.properties.security.protocol                     | WSPROXY_KAFKA_SECURITY_PROTOCOL          | `PLAINTEXT`  |
| kafka.ws.proxy.kafka-client.properties.sasl.mechanism                        | WSPROXY_KAFKA_SASL_MECHANISM             |  not set     |
| kafka.ws.proxy.kafka-client.properties.sasl.jaas.config                      | WSPROXY_KAFKA_SASL_JAAS_CFG              |  not set     |
| kafka.ws.proxy.kafka-client.properties.sasl.kerberos.service.name            | WSPROXY_KAFKA_SASL_KERBEROS_SERVICE_NAME |  not set     |
| kafka.ws.proxy.kafka-client.properties.ssl.key.password                      | WSPROXY_KAFKA_SSL_KEY_PASS               |  not set     |
| kafka.ws.proxy.kafka-client.properties.ssl.endpoint.identification.algorithm | WSPROXY_KAFKA_SASL_ENDPOINT_ID_ALOGO     |  not set     |
| kafka.ws.proxy.kafka-client.properties.ssl.truststore.location               | WSPROXY_KAFKA_SSL_TRUSTSTORE_LOCATION    |  not set     |
| kafka.ws.proxy.kafka-client.properties.ssl.truststore.truststore.password    | WSPROXY_KAFKA_SSL_TRUSTSTORE_PASS        |  not set     |
| kafka.ws.proxy.kafka-client.properties.ssl.keystore.location                 | WSPROXY_KAFKA_SSL_KEYSTORE_LOCATION      |  not set     |
| kafka.ws.proxy.kafka-client.properties.ssl.keystore.password                 | WSPROXY_KAFKA_SSL_KEYSTORE_PASS          |  not set     |
| kafka.ws.proxy.kafka-client.properties.ssl.provider                          | WSPROXY_KAFKA_SSL_PROVIDER               |  not set     |
| kafka.ws.proxy.kafka-client.properties.ssl.cipher.suites                     | WSPROXY_KAFKA_SSL_CIPHER_SUITES          |  not set     |
| kafka.ws.proxy.kafka-client.properties.ssl.enabled.protocols                 | WSPROXY_KAFKA_SSL_ENABLED_PROTOCOLS      |  not set     |

Additionally, each of the different clients (admin, producer and consumer), can
be configured individually. However, _these configurations are not currently
exposed as environment variables_.

The client specific configuration keys have the same structure as the
`kafka.ws.proxy.kafka-client.properties` key:

* `kafka.ws.proxy.admin-client.kafka-client-properties`
* `kafka.ws.proxy.consumer.kafka-client-properties`
* `kafka.ws.proxy.producer.kafka-client-properties`


### Confluent Metrics Reporter

If the property `kafka.ws.proxy.kafka-client.metrics-enabled` is set to `true`,
the proxy service can be configured to send metrics data to a different cluster.
The cluster can be differently configured, and it is therefore necessary to
provide a distinct client configuration for the metrics reporter.
  

| Config key                                                                                     | Environment                                      | Default      |
|:---                                                                                            |:----                                             |:------------:|
| kafka.ws.proxy.kafka-client.confluent-metrics.bootstrap-hosts                                  | WSPROXY_KAFKA_METRICS_BOOTSTRAP_HOSTS            | same as kafka.ws.proxy.kafka-client.bootstrap-hosts |
| kafka.ws.proxy.kafka-client.confluent-metrics.properties.security.protocol                     | WSPROXY_KAFKA_METRICS_SECURITY_PROTOCOL          | `PLAINTEXT`  |
| kafka.ws.proxy.kafka-client.confluent-metrics.properties.sasl.mechanism                        | WSPROXY_KAFKA_METRICS_SASL_MECHANISM             |  not set     |
| kafka.ws.proxy.kafka-client.confluent-metrics.properties.sasl.jaas.config                      | WSPROXY_KAFKA_METRICS_SASL_JAAS_CFG              |  not set     |
| kafka.ws.proxy.kafka-client.confluent-metrics.properties.sasl.kerberos.service.name            | WSPROXY_KAFKA_METRICS_SASL_KERBEROS_SERVICE_NAME |  not set     |
| kafka.ws.proxy.kafka-client.confluent-metrics.properties.ssl.key.password                      | WSPROXY_KAFKA_METRICS_SSL_KEY_PASS               |  not set     |
| kafka.ws.proxy.kafka-client.confluent-metrics.properties.ssl.endpoint.identification.algorithm | WSPROXY_KAFKA_METRICS_SASL_ENDPOINT_ID_ALOGO     |  not set     |
| kafka.ws.proxy.kafka-client.confluent-metrics.properties.ssl.truststore.location               | WSPROXY_KAFKA_METRICS_SSL_TRUSTSTORE_LOCATION    |  not set     |
| kafka.ws.proxy.kafka-client.confluent-metrics.properties.ssl.truststore.truststore.password    | WSPROXY_KAFKA_METRICS_SSL_TRUSTSTORE_PASS        |  not set     |
| kafka.ws.proxy.kafka-client.confluent-metrics.properties.ssl.keystore.location                 | WSPROXY_KAFKA_METRICS_SSL_KEYSTORE_LOCATION      |  not set     |
| kafka.ws.proxy.kafka-client.confluent-metrics.properties.ssl.keystore.password                 | WSPROXY_KAFKA_METRICS_SSL_KEYSTORE_PASS          |  not set     |
| kafka.ws.proxy.kafka-client.confluent-metrics.properties.ssl.provider                          | WSPROXY_KAFKA_METRICS_SSL_PROVIDER               |  not set     |
| kafka.ws.proxy.kafka-client.confluent-metrics.properties.ssl.cipher.suites                     | WSPROXY_KAFKA_METRICS_SSL_CIPHER_SUITES          |  not set     |
| kafka.ws.proxy.kafka-client.confluent-metrics.properties.ssl.enabled.protocols                 | WSPROXY_KAFKA_METRICS_SSL_ENABLED_PROTOCOLS      |  not set     |

## Endpoints and API


### WebSocket APIs

All WebSocket endpoints are available at the following base URL:

```
ws://<hostname>:<port>/socket
```

**Format types**

The below table shows which data types are allowed as both key and value in
both inbound and outbound messages.

| Value     | Serde      | JSON type   |
|:--------- |:-----------|:----------- |
| json      | string     | json        |
| avro      | byte[]     | base64      |
| protobuf  | byte[]     | base64      |
| bytearray | byte[]     | base64      |
| string    | string     | string      |
| int       | int        | number      |
| short     | short      | number      |
| long      | long       | number      |
| double    | double     | number      |
| float     | float      | number      |

#### `/in`

**Query parameters**:

| Name    | Type        | Required | Default value |
|:------- |:----------- |:--------:|:------------- |
| topic   | string      |     y    |               |
| keyType | format type |     n    |               |
| valType | format type |     y    |               |

##### Input

```json
{
  "key": {
    "value":"foo",
    "format":"string"
  },
  "value": {
    "value":"bar",
    "format":"string"
  }
}
```

##### Output

```json
{
  "topic": "foo",
  "partition": 2,
  "offset": 4,
  "timestamp": 1554896731782
}
```

#### `/out`                                   

**Query parameters**:

| Name                | Type        | Required | Default value |
|:------------------- |:----------- |:--------:|:------------- |
| clientId            | string      |    y     |               |
| groupId             | string      |    n     |               |
| topic               | string      |    y     |               |
| keyType             | format type |    n     |               |
| valType             | format type |    y     |               |
| offsetResetStrategy | string      |    n     | earliest      |
| rate                | integer     |    n     |               |
| batchSize           | integer     |    n     |               |
| autoCommit          | boolean     |    n     | true          |

##### Output

```json
{
  "wsProxyMessageId": "foo-0-1-1554402266846",
  "topic": "foobar",
  "partition": 0,
  "offset": 1,
  "timestamp": 1554402266846,
  "key": { // optional
    "value": "foo",
    "format": "string" // optional
  },
  "value": {
    "value": "bar",
    "format": "string" // optional
  }
}
```

##### Input

```json
{"wsProxyMessageId":"foo-0-1-1554402266846"}
```

### HTTP endpoints

#### Avro payload schemas

An alternative to the default JSON payloads used by the WebSocket proxy, one can
use a set of predefined Avro schemas to send and receive data. Below are
endpoints where the schemas for these Avro _wrapper_ messages can be found. 

##### `/schemas/avro/producer/record`

Returns the Avro wrapper schema to use when sending Avro serialised data to
Kafka. The wrapper message is used by the WebSocket proxy to ensure that both
the key and value are captured in a single message through the socket.

```json
{
  "doc": "Inbound schema for producing messages with a key and value to Kafka topics via the WebSocket proxy. It is up to the client to serialize the key and value before adding them to this message. This is because Avro does not support referencing external/remote schemas.",
  "fields": [
    {
      "name": "key",
      "type": [
        "null",
        "bytes"
      ]
    },
    {
      "name": "value",
      "type": "bytes"
    }
  ],
  "name": "AvroProducerRecord",
  "namespace": "net.scalytica.kafka.wsproxy.avro.SchemaTypes",
  "type": "record"
}
```

##### `/schemas/avro/producer/result`

Returns the Avro result schema containing metadata about the produced message.

```json
{
  "doc": "Outbound schema for responding to produced messages.",
  "fields": [
    {
      "name": "topic",
      "type": "string"
    },
    {
      "name": "partition",
      "type": "int"
    },
    {
      "name": "offset",
      "type": "long"
    },
    {
      "name": "timestamp",
      "type": "long"
    }
  ],
  "name": "AvroProducerResult",
  "namespace": "net.scalytica.kafka.wsproxy.avro.SchemaTypes",
  "type": "record"
}
```

##### `/schemas/avro/consumer/record`

Returns the Avro wrapper schema used for sending consumed messages over the
WebSocket.

```json
{
  "doc": "Outbound schema for messages with Avro key and value. It is up to the client to deserialize the key and value using the correct schemas, since these are passed through as raw byte arrays in this wrapper message.",
  "fields": [
    {
      "name": "wsProxyMessageId",
      "type": "string"
    },
    {
      "name": "topic",
      "type": "string"
    },
    {
      "name": "partition",
      "type": "int"
    },
    {
      "name": "offset",
      "type": "long"
    },
    {
      "name": "timestamp",
      "type": "long"
    },
    {
      "name": "key",
      "type": [
        "null",
        "bytes"
      ]
    },
    {
      "name": "value",
      "type": "bytes"
    }
  ],
  "name": "AvroConsumerRecord",
  "namespace": "net.scalytica.kafka.wsproxy.avro.SchemaTypes",
  "type": "record"
}
```

##### `/schemas/avro/producer/commit`

Avro schema to use for committing offsets when consuming data with the query
parameter option `autoCommit` set to `false`.

```json
{
  "doc": "Inbound schema for committing the offset of consumed messages.",
  "fields": [
    {
      "name": "wsProxyMessageId",
      "type": "string"
    }
  ],
  "name": "AvroCommit",
  "namespace": "net.scalytica.kafka.wsproxy.avro.SchemaTypes",
  "type": "record"
}
```

#### `/kafka/cluster/info`

This is a convenience endpoint to verify that the service can access the brokers
in the cluster. 

##### Output

```json
[
  {
    "id":  0,
    "host": "kafka-host-1",
    "port": 9092,
    "rack": "rack-1"
  },
  {
    "id":  1,
    "host": "kafka-host-2",
    "port": 9092,
    "rack": "rack-2"
  },
  {
    "id":  2,
    "host": "kafka-host-3",
    "port": 9092,
    "rack": "rack-3"
  }
]

```


# Development

## Testing

Using a CLI tool called `wscat`, interacting with the `kafka-websocket-proxy` is
relatively simple. The tool is freely available on
[github](https://github.com/websockets/wscat), and is highly recommended.

```bash
npm install -g wscat
```


