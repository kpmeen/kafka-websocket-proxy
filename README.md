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

### Docker image

The `kafka-websocket-proxy` is available pre-installed in a docker image from
Docker Hub at [kpmeen/kafka-websocket-proxy](https://hub.docker.com/r/kpmeen/kafka-websocket-proxy/tags).

For configuration, please see [Configuration](#Configuration) for details on
which environment properties should be used.  

#### Healthcheck

To add a healthcheck to to the docker container, a command calling the
`/healthcheck` endpoint can be configured. Below is an example using docker-compose: 

```
services:
  ...

  kafka-ws-proxy:
    image: kpmeen/kafka-websocket-proxy:latest
    ...
    healthcheck:
      test: ['CMD-SHELL', 'curl -f http://localhost:8087/healthcheck || exit 1']
      interval: 30s
      timeout: 3s
      retries: 40
    ...

  ...
```

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


### Server Configuration

Basic properties allowing configurations of things related to the basic server.
Allows for changing things like network interface, port number, etc. 
 
| Config key                                                      | Environment                              | Default                  | Description   |
|:---                                                             |:----                                     |:------------------------:|:-----         |
| kafka.ws.proxy.server.server-id                                 | WSPROXY_SERVER_ID                        | `node-1`                 | A unique identifier for the specific kafka-websocket-proxy instance. |
| kafka.ws.proxy.server.bind-interface                            | WSPROXY_BIND_INTERFACE                   | `0.0.0.0`                | Network interface to bind unsecured traffic to. |
| kafka.ws.proxy.server.port                                      | WSPROXY_PORT                             | `8078`                   | Port where the unsecured endpoints will be available. |
| kafka.ws.proxy.server.broker-resolution-timeout                 | WSPROXY_BROKER_RESOLUTION_TIMEOUT        | `30 seconds`             | Timeout duration to wait for successful host resolution of Kafka brokers. |
| kafka.ws.proxy.server.broker-resolution-retries                 | WSPROXY_BROKER_RESOLUTION_RETRIES        | `25`                     | Max number of retries for host resolution of Kafka brokers. |
| kafka.ws.proxy.server.broker-resolution-retry-interval          | WSPROXY_BROKER_RESOLUTION_RETRY_INTERVAL | `1 second`               | Interval duration between retries when resolving the Kafka broker hosts. |

### Endpoint Security

#### Server TLS/SSL Configuration

The `kafka-websocket-proxy` can run with SSL enabled. When using self-signed
certificates it is important to provide the location and password for the JKS
keystore file. When the certificate is provided through a valid authority these
configuration properties can be omitted. 

| Config key                                  | Environment                   | Default   | Description   |
|:---                                         |:----                          |:---------:|:-----         |
| kafka.ws.proxy.server.ssl.ssl-only          | WSPROXY_SSL_ONLY              | `false`   | Indicates if the server should use SSL/TLS only binding when SSL/TLS is enabled. |
| kafka.ws.proxy.server.ssl.bind-interface    | WSPROXY_SSL_BIND_INTERFACE    | `0.0.0.0` | Network interface to bind the SSL/TLS traffic to. |
| kafka.ws.proxy.server.ssl.port              | WSPROXY_SSL_PORT              | not set   | Port where the SSL/TLS endpoints will be available. |
| kafka.ws.proxy.server.ssl.keystore-location | WSPROXY_SSL_KEYSTORE_LOCATION | not set   | File path to location of key store file when using self-signed certificates. |
| kafka.ws.proxy.server.ssl.keystore-password | WSPROXY_SSL_KEYSTORE_PASS     | not set   | Password for the key store file. |

#### Basic Authentication

> **Warning**
>
> Make sure the proxy is configured to use SSL/TLS. Otherwise, the credentials
> are transferred in plain text.
> For production environments the `kafka.ws.proxy.server.ssl.ssl-only` property
> should be set to `true`.

| Config key                                | Environment                 | Default | Description   |
|:---                                       |:----                        |:-------:|:-----         |
| kafka.ws.proxy.server.basic-auth.enabled  | WSPROXY_BASIC_AUTH_ENABLED  | `false` | Indicates if the server should use basic authentication for the endpoints. |
| kafka.ws.proxy.server.basic-auth.realm    | WSPROXY_BASIC_AUTH_REALM    | not set | The realm to use for basic authentication. |
| kafka.ws.proxy.server.basic-auth.username | WSPROXY_BASIC_AUTH_USERNAME | not set | The username to use for basic authentication. |
| kafka.ws.proxy.server.basic-auth.password | WSPROXY_BASIC_AUTH_PASSWORD | not set | The password to use for basic authentication. |


#### OpenID Connect

> **Warning**
>
> Make sure the proxy is configured to use SSL/TLS. Otherwise, the credentials
> are transferred in plain text.
> For production environments the `kafka.ws.proxy.server.ssl.ssl-only` property
> should be set to `true`.

| Config key                                          | Environment              | Default | Description   |
|:---                                                 |:----                     |:-------:|:-----         |
| kafka.ws.proxy.server.openid-connect.enabled        | WSPROXY_OPENID_ENABLED   | `false` | Indicates if the server should use OpenID Connect to authenticate Bearer tokens for the endpoints. |
| kafka.ws.proxy.server.openid-connect.well-known-url | WSPROXY_OPENID_WELLKNOWN | not set | The full URL pointing to the OIDC `.well-known` OIDC configuration. |
| kafka.ws.proxy.server.openid-connect.audience       | WSPROXY_OPENID_AUDIENCE  | not set | The OIDC audience to be used when communicating with the OIDC server. |
| kafka.ws.proxy.server.openid-connect.realm          | WSPROXY_OPENID_REALM     | `""`    | Optional configuration that isn't really used by OIDC, but it's present in akka-http for API consistency. If not set, an empty string will be used. |


### Internal Session Handler

The `kafka-websocket-proxy` needs to keep some state about the different active
sessions across a multi-node deployment. The state is synced to other nodes
through a dedicated Kafka topic and kept up to date in each node in an in-memory
data structure. This allows e.g. controlling the number of open WebSockets in
a given consumer group, etc.

#### Properties

| Config key                                                             | Environment                                     | Default                  | Description   |
|:---                                                                    |:----                                            |:------------------------:|:-----         |
| kafka.ws.proxy.session-handler.session-state-topic-name                | WSPROXY_SESSION_STATE_TOPIC                     | `_wsproxy.session.state` | The name of the compacted topic where session state is kept. |
| kafka.ws.proxy.session-handler.session-state-replication-factor        | WSPROXY_SESSION_STATE_REPLICATION_FACTOR        | `3`                      | How many replicas to keep for the session state topic. |
| kafka.ws.proxy.session-handler.session-state-retention                 | WSPROXY_SESSION_STATE_RETENTION                 | `30 days`                | How long to keep sessions in the session state topic. |
| kafka.ws.proxy.session-handler.session-state-topic-init-timeout        | WSPROXY_SESSION_STATE_TOPIC_INIT_TIMEOUT        | `30 seconds`             | Timeout duration to wait for initialising the session state topic. |
| kafka.ws.proxy.session-handler.session-state-topic-init-retries        | WSPROXY_SESSION_STATE_TOPIC_INIT_RETRIES        | `25`                     | Max number of retries for initialising the session state topic. |
| kafka.ws.proxy.session-handler.session-state-topic-init-retry-interval | WSPROXY_SESSION_STATE_TOPIC_INIT_RETRY_INTERVAL | `1 second`               | Interval duration between retries when trying to initialise the session state topic. |

#### Manual creation of the session state topic

To enable persistent storage and distribution to other nodes, the
`kafka-websocket-proxy` relies on a compacted topic in Kafka. In most
circumstances, `kafka-websocket-proxy` will create the topic automatically.
When that is not possible because of ACL restrictions or similar, it is
necessary to create the topic manually.

To create the topic manually, the following properties **MUST** be set for the
topic:

##### Required properties

* **topic name**: <name of topic, must match `kafka.ws.proxy.session-handler.session-state-topic-name`.
* **cleanup policy**: compact
* **num partitions**: 1
  - Do _not_ set the partition count higher, since the proxy counts on guaranteed ordering.

##### Recommended properties

* **retention duration**: 2592000000 milliseconds (30 days)
* **replication factor**: 3
  - ¡IMPORTANT! Do not set replication factor higher than `<num kafka brokers> - 1`.

##### Example CLI command

```bash
kafka-topics \
  --bootstrap-server <kafka_host>:<port> \
  --create \
  --if-not-exists \
  --partitions 1 \
  --replication-factor 3 \
  --topic _wsproxy.session.state \
  --config cleanup.policy=compact \
  --config retention.ms=2592000000
```

### Internal Message Commit Handler

When a WebSocket client connects, it can specify if the auto-commit feature
should be used or not. In the case where the client opens the connection with
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

| Config key                                                                                           | Environment                              | Required | Default       | Description   |
|:---                                                                                                  |:----                                     |:--------:|:-------------:|:-----         |
| kafka.ws.proxy.kafka-client.bootstrap-hosts                                                          | WSPROXY_KAFKA_BOOTSTRAP_HOSTS            |    y     | not set       | A string with the Kafka brokers to bootstrap against, in the form `<host>:<port>`, separated by comma. |
| kafka.ws.proxy.kafka-client.schema-registry.url                                                      | WSPROXY_SCHEMA_REGISTRY_URL              |    n     | not set       | URLs for the Confluent Schema Registry. If _not_ set, any other schema registry configs will be ignored. |
| kafka.ws.proxy.kafka-client.schema-registry.auto-register-schemas                                    | WSPROXY_SCHEMA_AUTO_REGISTER             |    n     | `true`        | By default, the proxy will automatically register any internal Avro schemas it needs. If disabled, these schemas must be registered with the schema registry manually. |
| kafka.ws.proxy.kafka-client.schema-registry.properties.schema.registry.basic.auth.credentials.source | WSPROXY_SCHEMA_BASIC_AUTH_CREDS_SRC      |    n     | `USER_INFO`   | Basic auth mechanism to use for Confluent Schema Registry. |
| kafka.ws.proxy.kafka-client.schema-registry.properties.schema.registry.basic.auth.user.info          | WSPROXY_SCHEMA_BASIC_AUTH_USER_INFO      |    n     | `true`        | User info for basic auth against Confluent Schema Registry. |
| kafka.ws.proxy.kafka-client.properties.request.timeout.ms                                            | WSPROXY_KAFKA_CLIENT_REQUEST_TIMEOUT_MS  |    n     | `20000`       | Defines the request timeout period for the kafka clients. |
| kafka.ws.proxy.kafka-client.properties.retry.backoff.ms                                              | WSPROXY_KAFKA_CLIENT_RETRY_BACKOFF_MS    |    n     | `500`         | Defines the amount of time to wait before retrying a request. | 
| kafka.ws.proxy.kafka-client.monitoring-enabled                                                       | WSPROXY_CONFLUENT_MONITORING_ENABLED     |    n     | `false`       | When this flag is set to `true`, it will enable the Confluent Metrics Reporter |

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

#### Kafka cluster with authorization restrictions

For optimal operations the following permissions should be given to the
**principal** used by the `kafka-websocket-proxy`: 

| Operation        | Resource  | Required | Description                                                                                |
|:----------       |:----------|:--------:|:-----                                                                                      |
| DESCRIBE         | Cluster   |   Yes    | Used to query the cluster state                                                            |
| DESCRIBE_CONFIGS | Cluster   |   Yes    | Used to query the cluster state                                                            |
| DESCRIBE         | Topic     |   Yes    | Used to calculate maximum number of websocket consumers a client can initiate              |
| DESCRIBE_CONFIGS | Topic     |   Yes    | Used to calculate maximum number of websocket consumers a client can initiate              |
| CREATE           | Topic     |    No    | If not allowed, the session state topic must be created manually before starting the proxy |
| READ             | Topic     |   Yes    | Can be restricted to the `kafka.ws.proxy.session-handler.session-state-topic-name` (defaults to `_wsproxy.session.state`), and `kafka.ws.proxy.kafka-client.confluent-monitoring.properties.interceptor.topic` (defaults to `_confluent-metrics`) if confluent metrics is enabled |
| WRITE            | Topic     |   Yes    | Can be restricted to the `kafka.ws.proxy.session-handler.session-state-topic-name` (defaults to `_wsproxy.session.state`), and `kafka.ws.proxy.kafka-client.confluent-monitoring.properties.interceptor.topic` (defaults to `_confluent-metrics`) if confluent metrics is enabled |
| DESCRIBE         | Group     |   Yes    |                                                                                            | 

### Confluent Metrics Reporter

If the property `kafka.ws.proxy.kafka-client.monitoring-enabled` is set to `true`,
the proxy service can be configured to send metrics data to a different cluster.
The cluster can be differently configured, and it is therefore necessary to
provide a distinct client configuration for the metrics reporter.
  

| Config key                                                                                        | Environment                                         | Default      |
|:---                                                                                               |:----                                                |:------------:|
| kafka.ws.proxy.kafka-client.confluent-monitoring.bootstrap-hosts                                  | WSPROXY_KAFKA_MONITORING_BOOTSTRAP_HOSTS            | same as kafka.ws.proxy.kafka-client.bootstrap-hosts |
| kafka.ws.proxy.kafka-client.confluent-monitoring.properties.interceptor.topic                     | WSPROXY_KAFKA_MONITORING_INTERCEPTOR_TOPIC          | `_confluent-metrics` |
| kafka.ws.proxy.kafka-client.confluent-monitoring.properties.security.protocol                     | WSPROXY_KAFKA_MONITORING_SECURITY_PROTOCOL          | `PLAINTEXT`  |
| kafka.ws.proxy.kafka-client.confluent-monitoring.properties.sasl.mechanism                        | WSPROXY_KAFKA_MONITORING_SASL_MECHANISM             |  not set     |
| kafka.ws.proxy.kafka-client.confluent-monitoring.properties.sasl.jaas.config                      | WSPROXY_KAFKA_MONITORING_SASL_JAAS_CFG              |  not set     |
| kafka.ws.proxy.kafka-client.confluent-monitoring.properties.sasl.kerberos.service.name            | WSPROXY_KAFKA_MONITORING_SASL_KERBEROS_SERVICE_NAME |  not set     |
| kafka.ws.proxy.kafka-client.confluent-monitoring.properties.ssl.key.password                      | WSPROXY_KAFKA_MONITORING_SSL_KEY_PASS               |  not set     |
| kafka.ws.proxy.kafka-client.confluent-monitoring.properties.ssl.endpoint.identification.algorithm | WSPROXY_KAFKA_MONITORING_SASL_ENDPOINT_ID_ALOGO     |  not set     |
| kafka.ws.proxy.kafka-client.confluent-monitoring.properties.ssl.truststore.location               | WSPROXY_KAFKA_MONITORING_SSL_TRUSTSTORE_LOCATION    |  not set     |
| kafka.ws.proxy.kafka-client.confluent-monitoring.properties.ssl.truststore.truststore.password    | WSPROXY_KAFKA_MONITORING_SSL_TRUSTSTORE_PASS        |  not set     |
| kafka.ws.proxy.kafka-client.confluent-monitoring.properties.ssl.keystore.location                 | WSPROXY_KAFKA_MONITORING_SSL_KEYSTORE_LOCATION      |  not set     |
| kafka.ws.proxy.kafka-client.confluent-monitoring.properties.ssl.keystore.password                 | WSPROXY_KAFKA_MONITORING_SSL_KEYSTORE_PASS          |  not set     |
| kafka.ws.proxy.kafka-client.confluent-monitoring.properties.ssl.provider                          | WSPROXY_KAFKA_MONITORING_SSL_PROVIDER               |  not set     |
| kafka.ws.proxy.kafka-client.confluent-monitoring.properties.ssl.cipher.suites                     | WSPROXY_KAFKA_MONITORING_SSL_CIPHER_SUITES          |  not set     |
| kafka.ws.proxy.kafka-client.confluent-monitoring.properties.ssl.enabled.protocols                 | WSPROXY_KAFKA_MONITORING_SSL_ENABLED_PROTOCOLS      |  not set     |

### Logging

The `kafka-websocket-proxy` uses Logback for logging, and comes pre-packaged
with a configuration file with reasonable defaults. If there is a need to use a
different configuration, there are 3 recommended options.

**1. Providing an external log configuration file**

If it is necessary for any reason to use a different log configuration, the
most common way of doing so is to pass in a JVM argument when starting the
application.

The application accepts the standard Logback argument
`-Dlogback.configurationFile=<file_path>` to reference a different config file.

This argument can either be set explicitly in the `bin/server` that comes with
the distribution. Or, more easily, added to the `JAVA_OPTS` environment variable.  

**2. Overriding log levels for predefined loggers**

It is possible to set the log levels of some important loggers through environment
variables. The below table shows which are available, and what their default values are.

| Logger                       | Environment                      | Default |
|:---                          |:----                             |:-------:|
| akka.actor                   | WS_PROXY_AKKA_ACTOR_LOG_LEVEL    |  WARN   |
| akka.kafka                   | WS_PROXY_AKKA_KAFKA_LOG_LEVEL    |  WARN   |
| org.apache.kafka.clients     | WS_PROXY_KAFKA_CLIENTS_LOG_LEVEL |  ERROR  |
| net.scalytica.kafka.wsproxy  | WS_PROXY_APP_LOG_LEVEL           |  DEBUG  |
| root                         | WS_PROXY_ROOT_LOG_LEVEL          |  ERROR  |

**2. Overriding full configuration through environment** 

Another option that is useful when running the application in a docker container,
or another environment where configuration is primarily done through
environment variables, is the environment variable `WSPROXY_LOGBACK_XML_CONFIG`.

When the `WSPROXY_LOGBACK_XML_CONFIG` variable has a value, all other log
configurations will be ignored. So if e.g. both `WSPROXY_LOGBACK_XML_CONFIG` and
`WS_PROXY_KAFKA_CLIENTS_LOG_LEVEL` are set, the latter will be ignored completely.
The same applies when an external logback configuration file is provided through
`-Dlogback.configurationFile=<file_path>`.


## Endpoints and API

### WebSocket APIs


_TODO: Document query parameters for produce/consume_

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
| bytearray | byte[]     | base64      |
| string    | string     | string      |
| int       | int        | number      |
| short     | short      | number      |
| long      | long       | number      |
| double    | double     | number      |
| float     | float      | number      |


**Payload types**

| Value     | description                                                                                                 |
|:--------- |:----------------------------------------------------------------------------------------------------------- |
| json      | The default payload type to send via the WebSockets. See the endpoint docs for examples for JSON messages   |
| avro      | Used when WebSocket payload uses the Avro format described in [Avro Payload Schemas](#avro-payload-schemas) |

#### `/in`

**Headers**:

| Name          | Type                              | Required | Description   |
|:-------       |:-----------                       |:--------:|:------------- |
| Authorization | Basic authentication (Base64)     |     n    | [Basic authentication](https://en.wikipedia.org/wiki/Basic_access_authentication) header. |
| X-Kafka-Auth  | Base64                            |     n    | Header for providing Base64 representation of credentials in the form `username:password` to use for the Kafka connection when the topic has ACL restrictions. |

> **Warning**
>
> When providing `Authorization` with basic authentication, or Kafka ACL
> credentials via the `X-Kafka-Auth` header, make sure the proxy is configured
> to use SSL/TLS. This is because header credentials are transferred in plain
> text, as for regular HTTP basic authentication.

**Query parameters**:

| Name          | Type         | Required | Default value |
|:------------- |:-----------  |:--------:|:------------- |
| clientId      | string       |     y    |               |
| topic         | string       |     y    |               |
| socketPayload | payload type |     n    |      json     |
| clientId      | string       |     n    |               |
| keyType       | format type  |     n    |               |
| valType       | format type  |     y    |               |

##### Input (JSON)

```json
{
  "headers": [ { // optional
    "key": "my_header",
    "value": "header_value"
  } ],
  "key": { // optional
    "value": "foo",
    "format": "string"
  },
  "value": {
    "value": "bar",
    "format": "string"
  },
  "messageId": "client_generated_id" // optional
}
```

##### Output (JSON)

```json
{
  "topic": "foo",
  "partition": 2,
  "offset": 4,
  "timestamp": 1554896731782,
  "messageId":"client_generated_id" // optional
}
```

#### `/out`

**Headers**:

| Name          | Type                              | Required | Description   |
|:-------       |:-----------                       |:--------:|:------------- |
| Authorization | Basic authentication (Base64)     |     n    | [Basic authentication](https://en.wikipedia.org/wiki/Basic_access_authentication) header. |
| X-Kafka-Auth  | Base64                            |     n    | Header for providing Base64 representation of credentials in the form `username:password` to use for secure Kafka connections and when the topic has ACL restrictions. |

> **Warning**
>
> When providing `Authorization` with basic authentication, or Kafka ACL
> credentials via the `X-Kafka-Auth` header, make sure the proxy is configured
> to use SSL/TLS. This is because header credentials are transferred in plain
> text, as for regular HTTP basic authentication.

**Query parameters**:

| Name                | Type         | Required | Default value |
|:------------------- |:------------ |:--------:|:------------- |
| clientId            | string       |    y     |               |
| groupId             | string       |    n     |               |
| topic               | string       |    y     |               |
| socketPayload       | payload type |     n    |      json     |
| keyType             | format type  |    n     |               |
| valType             | format type  |    y     |               |
| offsetResetStrategy | string       |    n     |   earliest    |
| rate                | integer      |    n     |               |
| batchSize           | integer      |    n     |               |
| autoCommit          | boolean      |    n     |     true      |

##### Output (JSON)

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

##### Input (JSON)

```json
{
  "wsProxyMessageId": "foo-0-1-1554402266846"
}
```

### HTTP endpoints

**Headers**:

All HTTP endpoints support the following headers:

| Name          | Type                              | Required | Description   |
|:-------       |:-----------                       |:--------:|:------------- |
| Authorization | Basic authentication (Base64)     |     n    | [Basic authentication](https://en.wikipedia.org/wiki/Basic_access_authentication) header. |

> **Warning**
>
> When providing `Authorization` with basic authentication, make sure the proxy
> is configured to use SSL/TLS. This is because header credentials are
> transferred in plain text, as for regular HTTP basic authentication.

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
  "type" : "record",
  "name" : "AvroProducerRecord",
  "namespace" : "net.scalytica.kafka.wsproxy.avro",
  "doc" : "Inbound schema for producing messages with a key and value to Kafka topics via the WebSocket proxy. It is up to the client to serialize the key and value before adding them to this message. This is because Avro does not support referencing external/remote schemas.",
  "fields" : [ {
    "name" : "key",
    "type" : [ "null", "bytes", "string", "int", "long", "double", "float" ],
    "default" : null
  }, {
    "name" : "value",
    "type" : [ "bytes", "string", "int", "long", "double", "float" ]
  }, {
    "name" : "headers",
    "type" : [ "null", {
      "type" : "array",
      "items" : {
        "type" : "record",
        "name" : "KafkaMessageHeader",
        "doc" : "Schema definition for simple Kafka message headers.",
        "fields" : [ {
          "name" : "key",
          "type" : "string"
        }, {
          "name" : "value",
          "type" : "string"
        } ]
      }
    } ],
    "default" : null
  }, {
    "name" : "clientMessageId",
    "type" : [ "null", "string" ],
    "default" : null
  } ]
}
```

##### `/schemas/avro/producer/result`

Returns the Avro result schema containing metadata about the produced message.

```json
{
  "type" : "record",
  "name" : "AvroProducerResult",
  "namespace" : "net.scalytica.kafka.wsproxy.avro",
  "doc" : "Outbound schema for responding to produced messages.",
  "fields" : [ {
    "name" : "topic",
    "type" : "string"
  }, {
    "name" : "partition",
    "type" : "int"
  }, {
    "name" : "offset",
    "type" : "long"
  }, {
    "name" : "timestamp",
    "type" : "long"
  }, {
    "name" : "clientMessageId",
    "type" : [ "null", "string" ],
    "default" : null
  } ]
}
```

##### `/schemas/avro/consumer/record`

Returns the Avro wrapper schema used for sending consumed messages over the
WebSocket.

```json
{
  "type" : "record",
  "name" : "AvroConsumerRecord",
  "namespace" : "net.scalytica.kafka.wsproxy.avro",
  "doc" : "Outbound schema for messages with Avro key and value. It is up to the client to deserialize the key and value using the correct schemas, since these are passed through as raw byte arrays in this wrapper message.",
  "fields" : [ {
    "name" : "wsProxyMessageId",
    "type" : "string"
  }, {
    "name" : "topic",
    "type" : "string"
  }, {
    "name" : "partition",
    "type" : "int"
  }, {
    "name" : "offset",
    "type" : "long"
  }, {
    "name" : "timestamp",
    "type" : "long"
  }, {
    "name" : "key",
    "type" : [ "null", "bytes", "string", "int", "long", "double", "float" ]
  }, {
    "name" : "value",
    "type" : [ "bytes", "string", "int", "long", "double", "float" ]
  }, {
    "name" : "headers",
    "type" : [ "null", {
      "type" : "array",
      "items" : {
        "type" : "record",
        "name" : "KafkaMessageHeader",
        "doc" : "Schema definition for simple Kafka message headers.",
        "fields" : [ {
          "name" : "key",
          "type" : "string"
        }, {
          "name" : "value",
          "type" : "string"
        } ]
      }
    } ]
  } ]
}
```

##### `/schemas/avro/producer/commit`

Avro schema to use for committing offsets when consuming data with the query
parameter option `autoCommit` set to `false`.

```json
{
  "type" : "record",
  "name" : "AvroCommit",
  "namespace" : "net.scalytica.kafka.wsproxy.avro",
  "doc" : "Inbound schema for committing the offset of consumed messages.",
  "fields" : [ {
    "name" : "wsProxyMessageId",
    "type" : "string"
  } ]
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

#### `/healthcheck`

Simple endpoint to see if the service is up and running. It _does not_ verify
connectivity with the Kafka brokers. If this is necessary, use the
`/kafka/cluster/info` endpoint.

##### Output

```json
{ "response": "I'm healthy" }
```

# Development

## Testing

Using a CLI tool called `wscat`, interacting with the `kafka-websocket-proxy` is
relatively simple. The tool is freely available on
[github](https://github.com/websockets/wscat), and is highly recommended.

```bash
npm install -g wscat
```
