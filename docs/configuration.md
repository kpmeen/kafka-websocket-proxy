---
id: configuration
title: Configuration
slug: /configuration
---

The Kafka WebSocket Proxy is built on Apache Pekko, which is a fork of the Akka project. More specifically:

* [Apache Pekko HTTP](https://pekko.apache.org/docs/pekko-http/current//server-side/websocket-support.html)
* [Apache Pekko Streams](https://pekko.apache.org/docs/pekko/current//stream/index.html)
* [Apache Pekko Connectors Kafka connector](https://pekko.apache.org/docs/pekko-connectors-kafka/current/index.html)

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


## Server Configuration

Basic properties allowing configurations of things related to the basic server.
Allows for changing things like network interface, port number, etc.

| Config key                                             | Environment                              |   Default    | Description                                                                                                                                   |
|:-------------------------------------------------------|:-----------------------------------------|:------------:|:----------------------------------------------------------------------------------------------------------------------------------------------|
| kafka.ws.proxy.server.server-id                        | WSPROXY_SERVER_ID                        |   `node-1`   | A unique identifier for the specific kafka-websocket-proxy instance.                                                                          |
| kafka.ws.proxy.server.bind-interface                   | WSPROXY_BIND_INTERFACE                   |  `0.0.0.0`   | Default network interface to bind traffic to.                                                                                                 |
| kafka.ws.proxy.server.port                             | WSPROXY_PORT                             |    `8078`    | Port where the unsecured endpoints will be available.                                                                                         |
| kafka.ws.proxy.server.broker-resolution-timeout        | WSPROXY_BROKER_RESOLUTION_TIMEOUT        | `30 seconds` | Timeout duration to wait for successful host resolution of Kafka brokers.                                                                     |
| kafka.ws.proxy.server.broker-resolution-retries        | WSPROXY_BROKER_RESOLUTION_RETRIES        |     `25`     | Max number of retries for host resolution of Kafka brokers.                                                                                   |
| kafka.ws.proxy.server.broker-resolution-retry-interval | WSPROXY_BROKER_RESOLUTION_RETRY_INTERVAL |  `1 second`  | Interval duration between retries when resolving the Kafka broker hosts.                                                                      |
| kafka.ws.proxy.server.secure-health-check-endpoint     | WSPROXY_SECURE_HEALTHCHECK_ENDPOINT      |    `true`    | When set to `true`, will enforce the same auth requirements as other endpoints. If `false` the `/healthcheck` endpoint will not require auth. |
| kafka.ws.proxy.server.jmx.proxy.status.interval        | WSPROXY_JMX_PROXY_STATUS_INTERVAL        | `5 seconds`  | Sets the frequency the Kafka WebSocket Proxy will update the values in the `ProxyStatusMXBean`                                                |

### Admin Server Configuration

The admin server provides a set of service endpoints that should not be exposed
together with the standard endpoints. Typically, these services provide
information or functionality that can be considered harmful for unauthorised
use.

These endpoints must be explicitly enabled before they can be used. And when
they are enabled, will be exposed on a separate port number.

> NOTE:
> If the server is configured to use SSL/TLS, the admin server will
> automatically use it as well. This also applies to configs where both SSL/TLS
> and plain access to the proxy is allowed.

| Config key                                 | Environment                    |                       Default                        | Description                                                       |
|:-------------------------------------------|:-------------------------------|:----------------------------------------------------:|:------------------------------------------------------------------|
| kafka.ws.proxy.server.admin.enabled        | WSPROXY_ADMIN_ENDPOINT_ENABLED |                       `false`                        | To enable the admin server endpoints this must be set to  `true`. |
| kafka.ws.proxy.server.admin.bind-interface | WSPROXY_ADMIN_BIND_INTERFACE   | same value as `kafka.ws.proxy.server.bind-interface` | Network interface to bind traffic to.                             |
| kafka.ws.proxy.server.port                 | WSPROXY_PORT                   |                        `9078`                        | Port where the admin endpoints will be available.                 |

## Internal Dynamic Configuration Handler

The `kafka-websocket-proxy` can be configured to allow dynamically setting client
specific configurations. To ensure that the configurations are distributed to
all instances in a multi-node deployment, the dynamic configurations are written
to a dedicated compacted topic in Kafka. Each instance keeps track of the
dynamic configurations in an in-memory data structure.

#### Properties

| Config key                                                      | Environment                                 |          Default           | Description                                                                                   |
|:----------------------------------------------------------------|:--------------------------------------------|:--------------------------:|:----------------------------------------------------------------------------------------------|
| kafka.ws.proxy.dynamic-config-handler.enabled                   | WSPROXY_DYNAMIC_CONFIG_HANDLER_ENABLED      |          `false`           | This feature is _disabled_ by default. Set to `true` to enable.                               |
| kafka.ws.proxy.dynamic-config-handler.topic-name                | WSPROXY_DYNAMIC_CONFIG_TOPIC_NAME           | `_wsproxy.dynamic.configs` | The name of the compacted topic where dynamic configurations are kept.                        |
| kafka.ws.proxy.dynamic-config-handler.topic-replication-factor  | WSPROXY_DYNAMIC_CONFIG_REPLICATION_FACTOR   |            `3`             | How many replicas to keep for the dynamic configurations topic.                               |
| kafka.ws.proxy.dynamic-config-handler.topic-retention           | WSPROXY_DYNAMIC_CONFIG_RETENTION            |            `0`             | How long to keep configurations in the dynamic configurations topic. Defaults to infinite.    |
| kafka.ws.proxy.dynamic-config-handler.topic-init-timeout        | WSPROXY_DYNAMIC_CONFIG_TOPIC_INIT_TIMEOUT   |        `30 seconds`        | Timeout duration to wait for initialising the dynamic configurations topic.                   |
| kafka.ws.proxy.dynamic-config-handler.topic-init-retries        | WSPROXY_DYNAMIC_CONFIG_TOPIC_INIT_RETRIES   |            `25`            | Max number of retries for initialising the dynamic configurations topic.                      |
| kafka.ws.proxy.dynamic-config-handler.topic-init-retry-interval | WSPROXY_DYNAMIC_CONFIG_TOPIC_RETRY_INTERVAL |         `1 second`         | Interval duration between retries when trying to initialise the dynamic configurations topic. |

### Manual creation of the dynamic configurations topic

To enable persistent storage and distribution to other nodes, the
`kafka-websocket-proxy` relies on a compacted topic in Kafka. In most
circumstances, `kafka-websocket-proxy` will create the topic automatically.
When that is not possible because of ACL restrictions or similar, it is
necessary to create the topic manually.

To create the topic manually, the following properties **MUST** be set for the
topic:

##### Required properties

* **topic name**: <name of topic, must match `kafka.ws.proxy.dynamic-config-handler.topic-name`.
* **cleanup policy**: `compact`
* **num partitions**: `1`
  - ¡IMPORTANT! Do **_not_** set the partition count higher.

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
  --topic _wsproxy.dynamic.configs \
  --config cleanup.policy=compact \
  --config retention.ms=0
```

## Internal Session Handler

The `kafka-websocket-proxy` needs to keep some state about the different active
sessions across a multi-node deployment. The state is synced to other nodes
through a dedicated Kafka topic and kept up to date in each node in an in-memory
data structure. This allows e.g. controlling the number of open WebSockets in
a given consumer group, etc.

#### Properties

| Config key                                               | Environment                                     |         Default          | Description                                                                          |
|:---------------------------------------------------------|:------------------------------------------------|:------------------------:|:-------------------------------------------------------------------------------------|
| kafka.ws.proxy.session-handler.topic-name                | WSPROXY_SESSION_STATE_TOPIC                     | `_wsproxy.session.state` | The name of the compacted topic where session state is kept.                         |
| kafka.ws.proxy.session-handler.topic-replication-factor  | WSPROXY_SESSION_STATE_REPLICATION_FACTOR        |           `3`            | How many replicas to keep for the session state topic.                               |
| kafka.ws.proxy.session-handler.topic-retention           | WSPROXY_SESSION_STATE_RETENTION                 |        `30 days`         | How long to keep sessions in the session state topic.                                |
| kafka.ws.proxy.session-handler.topic-init-timeout        | WSPROXY_SESSION_STATE_TOPIC_INIT_TIMEOUT        |       `30 seconds`       | Timeout duration to wait for initialising the session state topic.                   |
| kafka.ws.proxy.session-handler.topic-init-retries        | WSPROXY_SESSION_STATE_TOPIC_INIT_RETRIES        |           `25`           | Max number of retries for initialising the session state topic.                      |
| kafka.ws.proxy.session-handler.topic-init-retry-interval | WSPROXY_SESSION_STATE_TOPIC_INIT_RETRY_INTERVAL |        `1 second`        | Interval duration between retries when trying to initialise the session state topic. |

### Manual creation of the session state topic

To enable persistent storage and distribution to other nodes, the
`kafka-websocket-proxy` relies on a compacted topic in Kafka. In most
circumstances, `kafka-websocket-proxy` will create the topic automatically.
When that is not possible because of ACL restrictions or similar, it is
necessary to create the topic manually.

To create the topic manually, the following properties **MUST** be set for the
topic:

##### Required properties

* **topic name**: <name of topic, must match `kafka.ws.proxy.session-handler.topic-name`.
* **cleanup policy**: `compact`
* **num partitions**: `1`
    - ¡IMPORTANT! Do **_not_** set the partition count higher.

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

## Internal Message Commit Handler

When a WebSocket client connects, it can specify if the auto-commit feature
should be used or not. In the case where the client opens the connection with
`autoCommit=false` in the query parameters, the websocket will keep track of
the uncommitted message offsets in an in-memory "stack" structure in a _commit
handler_. This allows the client to send in a special _commit_ message on the
inbound WebSocket channel, that will trigger a given message offset to be
committed to Kafka.
The below properties allows to tune some of the parameters that affect the
behaviour of the commit handler

| Config key                                         | Environment                    |   Default    | Description                                                                                                        |
|:---------------------------------------------------|:-------------------------------|:------------:|:-------------------------------------------------------------------------------------------------------------------|
| kafka.ws.proxy.commit-handler.max-stack-size       | WSPROXY_CH_MAX_STACK_SIZE      |    `100`     | The maximum number of uncommitted messages, per partition, that will be kept track of in the commit handler stack. |
| kafka.ws.proxy.commit-handler.auto-commit-enabled  | WSPROXY_CH_AUTOCOMMIT_ENABLED  |   `false`    | Whether or not to allow the proxy to perform automatic offset commits of uncommitted messages.                     |
| kafka.ws.proxy.commit-handler.auto-commit-interval | WSPROXY_CH_AUTOCOMMIT_INTERVAL |  `1 second`  | The interval to execute the jobo for auto-committing messages of a given age.                                      |
| kafka.ws.proxy.commit-handler.auto-commit-max-age  | WSPROXY_CH_AUTOCOMMIT_MAX_AGE  | `20 seconds` | The max allowed age of uncommitted messages in the commit handler stack.                                           |


## Internal Kafka Client

Exposed configuration properties for the Kafka clients initialised and used by
the `kafka-websocket-proxy` whenever a WebSocket connection is established.

| Config key                                                                                           | Environment                             | Required |   Default    | Description                                                                                                                                                                                                                           |
|:-----------------------------------------------------------------------------------------------------|:----------------------------------------|:--------:|:------------:|:--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| kafka.ws.proxy.kafka-client.bootstrap-hosts                                                          | WSPROXY_KAFKA_BOOTSTRAP_HOSTS           |    y     |   not set    | A string with the Kafka brokers to bootstrap against, in the form `<host>:<port>`, separated by comma.                                                                                                                                |
| kafka.ws.proxy.kafka-client.schema-registry.url                                                      | WSPROXY_SCHEMA_REGISTRY_URL             |    n     |   not set    | URLs for the Confluent Schema Registry. If _not_ set, any other schema registry configs will be ignored.                                                                                                                              |
| kafka.ws.proxy.kafka-client.schema-registry.auto-register-schemas                                    | WSPROXY_SCHEMA_AUTO_REGISTER            |    n     |    `true`    | By default, the proxy will automatically register any internal schemas it needs. If disabled, these schemas must be registered with the schema registry manually.                                                                     |
| kafka.ws.proxy.kafka-client.schema-registry.properties.schema.registry.basic.auth.credentials.source | WSPROXY_SCHEMA_BASIC_AUTH_CREDS_SRC     |    n     | `USER_INFO`  | Basic auth mechanism to use for Confluent Schema Registry.                                                                                                                                                                            |
| kafka.ws.proxy.kafka-client.schema-registry.properties.schema.registry.basic.auth.user.info          | WSPROXY_SCHEMA_BASIC_AUTH_USER_INFO     |    n     |    `true`    | User info for basic auth against Confluent Schema Registry.                                                                                                                                                                           |
| kafka.ws.proxy.kafka-client.properties.request.timeout.ms                                            | WSPROXY_KAFKA_CLIENT_REQUEST_TIMEOUT_MS |    n     |   `30000`    | Defines the amount of time the client will wait for a response to a request. Note that this property affect consumer and producer clients differently. See official Kafka docs for more details.                                      |
| kafka.ws.proxy.kafka-client.properties.retries                                                       | WSPROXY_KAFKA_CLIENT_NUM_RETRIES        |    n     | `2147483647` | Setting a value greater than zero will cause the client to resend any record whose send fails with a potentially transient error. Note that this retry is no different than if the client resent the record upon receiving the error. |
| kafka.ws.proxy.kafka-client.properties.retry.backoff.ms                                              | WSPROXY_KAFKA_CLIENT_RETRY_BACKOFF_MS   |    n     |    `100`     | Defines the amount of time to wait before retrying a request.                                                                                                                                                                         |
| kafka.ws.proxy.kafka-client.monitoring-enabled                                                       | WSPROXY_CONFLUENT_MONITORING_ENABLED    |    n     |   `false`    | When this flag is set to `true`, it will enable the Confluent Metrics Reporter                                                                                                                                                        |

### Producer specific configuration

| Config key                                                                            | Environment                               | Required |   Default    | Description                                                                                                                                                                                                                                         |
|:--------------------------------------------------------------------------------------|:------------------------------------------|:--------:|:------------:|:----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| kafka.ws.proxy.producer.kafka-client-properties.request.timeout.ms                    | WSPROXY_KAFKA_PRODUCER_REQUEST_TIMEOUT_MS |    n     |   `30000`    | Defines the amount of time the client will wait for a response to a request. Note that this property affect consumer and producer clients differently. See official Kafka docs for more details.                                                    |
| kafka.ws.proxy.producer.kafka-client-properties.retries                               | WSPROXY_KAFKA_PRODUCER_NUM_RETRIES        |    n     | `2147483647` | Setting a value greater than zero will cause the client to resend any record whose send fails with a potentially transient error. Note that this retry is no different than if the client resent the record upon receiving the error.               |
| kafka.ws.proxy.producer.kafka-client-properties.retry.backoff.ms                      | WSPROXY_KAFKA_PRODUCER_RETRY_BACKOFF_MS   |    n     |    `100`     | Defines the amount of time to wait before retrying a request.                                                                                                                                                                                       |
| kafka.ws.proxy.producer.kafka-client-properties.delivery.timeout.ms                   | WSPROXY_KAFKA_PRODUCER_DELIVER_TIMEOUT_MS |    n     |   `120000`   | Defines the amount of time to wait before abandoning the attempt to deliver a message to Kafka.                                                                                                                                                     |
| kafka.ws.proxy.producer.kafka-client-properties.max.in.flight.requests.per.connection | WSPROXY_KAFKA_PRODUCER_MAX_IN_FLIGHT_REQ  |    n     |     `5`      | The maximum number of unacknowledged requests the client will send on a single connection before blocking. Note that if this setting is set to be greater than 1 and there are failed sends, there is a risk of message re-ordering due to retries. |
| kafka.ws.proxy.producer.kafka-client-properties.compression.type                      | WSPROXY_KAFKA_PRODUCER_COMPRESSION_TYPE   |    n     |    `none`    | Compression codec to use for the producer. Supported values are: `none`, `gzip`, `snappy`, `lz4`, or `zstd`.                                                                                                                                        |

##### WebSocket client limitations

> NOTE:
> Currently it is only possible to configure client specific limits in the
> `application.conf` file. This limitation will be addressed in future versions.

| Config key                                                        | Environment                                         | Required | Default | Description                                                                                                                                                                                                                                                                                                                                                               |
|:------------------------------------------------------------------|:----------------------------------------------------|:--------:|:-------:|:--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| kafka.ws.proxy.producer.sessions-enabled                          | WSPROXY_KAFKA_PRODUCER_SESSIONS_ENABLED             |    n     | `false` | Controls whether or not producer sessions will be enabled or not. Setting the value to `false` will bypass any session logic, including the max number of connections per producer client. Setting the value to `true` will allow the proxy to enforce client limitations on producers.                                                                                   |
| kafka.ws.proxy.producer.exactly-once-enabled                      | WSPROXY_PRODUCER_EXACTLY_ONCE_ENABLED               |    n     | `false` | Controls whether or not it will be possible to initiate websocket connections using a transactional Kafka producer.                                                                                                                                                                                                                                                       |
| kafka.ws.proxy.producer.limits.default-messages-per-second        | WSPROXY_PRODUCER_RATELIMIT_DEFAULT_MESSAGES_PER_SEC |    n     |   `0`   | Set the number of messages to allow through per second. Default value of `0` will disable default rate limiting.                                                                                                                                                                                                                                                          |
| kafka.ws.proxy.producer.limits.default-max-connections-per-client | WSPROXY_PRODUCER_DEFAULT_MAX_CLIENT_CONNECTIONS     |    n     |   `0`   | Set the maximum number of connections a given producer client ID can have. Default value of `0` will disable default connection limit.                                                                                                                                                                                                                                    |


### Consumer specific configuration

| Config key                                                         | Environment                               | Required |   Default    | Description                                                                                                                                                                                                                           |
|:-------------------------------------------------------------------|:------------------------------------------|:--------:|:------------:|:--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| kafka.ws.proxy.consumer.kafka-client-properties.request.timeout.ms | WSPROXY_KAFKA_CONSUMER_REQUEST_TIMEOUT_MS |    n     |   `30000`    | Defines the amount of time the client will wait for a response to a request. Note that this property affect consumer and producer clients differently. See official Kafka docs for more details.                                      |
| kafka.ws.proxy.consumer.kafka-client-properties.retries            | WSPROXY_KAFKA_CONSUMER_NUM_RETRIES        |    n     | `2147483647` | Setting a value greater than zero will cause the client to resend any record whose send fails with a potentially transient error. Note that this retry is no different than if the client resent the record upon receiving the error. |
| kafka.ws.proxy.consumer.kafka-client-properties.retry.backoff.ms   | WSPROXY_KAFKA_CONSUMER_RETRY_BACKOFF_MS   |    n     |    `100`     | Defines the amount of time to wait before retrying a request.                                                                                                                                                                         |

##### WebSocket client limitations

> NOTE:
> Currently it is only possible to configure client specific limits in the
> `application.conf` file. This limitation will be addressed in future versions.

| Config key                                                        | Environment                                         | Required | Default | Description                                                                                                                                                                                                                          |
|:------------------------------------------------------------------|:----------------------------------------------------|:--------:|:-------:|:-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| kafka.ws.proxy.consumer.limits.default-messages-per-second        | WSPROXY_CONSUMER_RATELIMIT_DEFAULT_MESSAGES_PER_SEC |    n     |   `0`   | Set the number of messages to allow through per second. Default value of `0` will disable default rate limiting.                                                                                                                     |
| kafka.ws.proxy.consumer.limits.default-max-connections-per-client | WSPROXY_CONSUMER_DEFAULT_MAX_CLIENT_CONNECTIONS     |    n     |   `0`   | Set the maximum number of connections a given consumer client ID can have. Default value of `0` will disable default connection limit. A consumer will nevertheless not be allowed more connections than there are topic partitions. |


## Endpoint Security

### Server TLS/SSL Configuration

The `kafka-websocket-proxy` can run with SSL enabled. When using self-signed
certificates it is important to provide the location and password for the JKS
keystore file. When the certificate is provided through a valid authority these
configuration properties can be omitted.

| Config key                                  | Environment                   |  Default  | Description                                                                      |
|:--------------------------------------------|:------------------------------|:---------:|:---------------------------------------------------------------------------------|
| kafka.ws.proxy.server.ssl.enabled           | WSPROXY_SSL_ENABLED           |  `false`  | Flag to turn on/off SSL for the proxy.                                           |
| kafka.ws.proxy.server.ssl.ssl-only          | WSPROXY_SSL_ONLY              |  `false`  | Indicates if the server should use SSL/TLS only binding when SSL/TLS is enabled. |
| kafka.ws.proxy.server.ssl.bind-interface    | WSPROXY_SSL_BIND_INTERFACE    | `0.0.0.0` | Network interface to bind the SSL/TLS traffic to.                                |
| kafka.ws.proxy.server.ssl.port              | WSPROXY_SSL_PORT              |  not set  | Port where the SSL/TLS endpoints will be available.                              |
| kafka.ws.proxy.server.ssl.keystore-location | WSPROXY_SSL_KEYSTORE_LOCATION |  not set  | File path to location of key store file when using self-signed certificates.     |
| kafka.ws.proxy.server.ssl.keystore-password | WSPROXY_SSL_KEYSTORE_PASS     |  not set  | Password for the key store file.                                                 |

### Basic Authentication

> **Warning**
>
> Make sure the proxy is configured to use SSL/TLS. Otherwise, the credentials
> are transferred in plain text.
> For production environments the `kafka.ws.proxy.server.ssl.ssl-only` property
> should be set to `true`.

| Config key                                | Environment                 | Default | Description                                                                |
|:------------------------------------------|:----------------------------|:-------:|:---------------------------------------------------------------------------|
| kafka.ws.proxy.server.basic-auth.enabled  | WSPROXY_BASIC_AUTH_ENABLED  | `false` | Indicates if the server should use basic authentication for the endpoints. |
| kafka.ws.proxy.server.basic-auth.realm    | WSPROXY_BASIC_AUTH_REALM    | not set | The realm to use for basic authentication.                                 |
| kafka.ws.proxy.server.basic-auth.username | WSPROXY_BASIC_AUTH_USERNAME | not set | The username to use for basic authentication.                              |
| kafka.ws.proxy.server.basic-auth.password | WSPROXY_BASIC_AUTH_PASSWORD | not set | The password to use for basic authentication.                              |


### OpenID Connect

> **Warning**
>
> Make sure the proxy is configured to use SSL/TLS. Otherwise, the credentials
> are transferred in plain text.
> For production environments the `kafka.ws.proxy.server.ssl.ssl-only` property
> should be set to `true`.

| Config key                                                     | Environment                              |   Default    | Description                                                                                                                                                                                                                       |
|:---------------------------------------------------------------|:-----------------------------------------|:------------:|:----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| kafka.ws.proxy.server.openid-connect.enabled                   | WSPROXY_OPENID_ENABLED                   |   `false`    | Indicates if the server should use OpenID Connect to authenticate Bearer tokens for the endpoints.                                                                                                                                |
| kafka.ws.proxy.server.openid-connect.well-known-url            | WSPROXY_OPENID_WELLKNOWN                 |   not set    | The full URL pointing to the OIDC `.well-known` OIDC configuration.                                                                                                                                                               |
| kafka.ws.proxy.server.openid-connect.audience                  | WSPROXY_OPENID_AUDIENCE                  |   not set    | The OIDC audience to be used when communicating with the OIDC server.                                                                                                                                                             |
| kafka.ws.proxy.server.openid-connect.realm                     | WSPROXY_OPENID_REALM                     |     `""`     | (Optional) Configuration that isn't really used by OIDC, but it's present in pekko-http for API consistency. If not set, an empty string will be used.                                                                            |
| kafka.ws.proxy.server.openid-connect.allow-detailed-logging    | WSPROXY_OPENID_ALLOW_DETAILED_LOGGING    |   `false`    | If set to `true` the proxy will log some details of the tokens being validated. Not recommended for use in production.                                                                                                            |
| kafka.ws.proxy.server.openid-connect.revalidation-interval     | WSPROXY_OPENID_REVALIDATION_INTERVAL     | `10 minutes` | The interval to verify that the JWT token is valid when a WebSocket connection is open.                                                                                                                                           |
| kafka.ws.proxy.server.openid-connect.revalidation-errors-limit | WSPROXY_OPENID_REVALIDATION_ERRORS_LIMIT |     `-1`     | The number of times the JWT validation check for an open WebSocket may fail due to e.g. OpenID Connect server being unavailable. Once the limit is reached, the connection is terminated. A value of `-1` will disable the limit. |

#### Revalidation of JWT token on open WebSocket connections

When OpenID Connect is enabled, `kafka-websocket-proxy` will periodically
revalidate the JWT token used for authentication. The duration of the interval
between revalidation is configurable. It is also possible to set an error limit
threshold for transient errors, like networking issues, between the proxy and
OIDC server. See the table above for details on these configuration parameters.

The JWT revalidation process will terminate the WebSocket connection in the
following scenarios:

* The JWT token is no longer valid.
* Revalidation fails due to a transient error when trying to communicate with the OIDC server, and the `kafka.ws.proxy.server.openid-connect.revalidation-errors-limit` has a value that is `>` than `-1`.

Note that the `kafka-websocket-proxy` has the revalidation error limit set to
`-1` by default. Meaning, it will _not_ disconnect open connections due to
transient errors.

#### Using JWT token as the bearer for Kafka credentials

Some OpenID services allow adding extra attributes to the JWT token being
provided to authenticated clients. In some cases these attributes can be used
to provide things like credentials and connection info to clients. The
`kafka-websocket-proxy` has built-in support for using credentials found in JWT
tokens to authenticate against Kafka.

To enable this feature, in addition to configuring OpenID Connect, the _attribute
key names_ for the username and password attributes must be provided. Once these
have been defined in the configuration, the `kafka-websocket-proxy` will attempt
to find the credentials in the JWT token _first_. If not successful, it will
look in the `X-Kafka-Auth` header for Base64 encoded credentials.

| Config key                                                             | Environment                        | Default | Description                                                                                                 |
|:-----------------------------------------------------------------------|:-----------------------------------|:-------:|:------------------------------------------------------------------------------------------------------------|
| kafka.ws.proxy.server.openid-connect.custom-jwt.kafka-token-auth-only  | WSPROXY_JWT_KAFKA_TOKEN_AUTH_ONLY  | `false` | When set to `true` the proxy will only allow Kafka authentication through the JWT token.                    |
| kafka.ws.proxy.server.openid-connect.custom-jwt.jwt-kafka-username-key | WSPROXY_JWT_KAFKA_USERNAME_KEY     | not set | (Optional) JWT attribute key name for the Kafka username when Kafka credentials are passed via a JWT token. |
| kafka.ws.proxy.server.openid-connect.custom-jwt.jwt-kafka-password-key | WSPROXY_JWT_KAFKA_PASSWORD_KEY     | not set | (Optional) JWT attribute key name for the Kafka password when Kafka credentials are passed via a JWT token. |

Example:

```
kafka.ws.proxy.server.openid-connect.custom-jwt {
  jwt-kafka-username-key = "net.scalytica.jwt.username"
  jwt-kafka-password-key = "net.scalytica.jwt.password"
}
```


## Kafka Security

The `kafka-websocket-proxy` allows setting Kafka client specific properties
under the key `kafka.ws.proxy.kafka-client.properties` in the `application.conf`
file. To connect to a secure Kafka cluster, the necessary security properties
should be added here. Below is a table containing the properties that are
currently possible to set using specific environment variables:


| Config key                                                                   | Environment                              |   Default   |
|:-----------------------------------------------------------------------------|:-----------------------------------------|:-----------:|
| kafka.ws.proxy.kafka-client.properties.security.protocol                     | WSPROXY_KAFKA_SECURITY_PROTOCOL          | `PLAINTEXT` |
| kafka.ws.proxy.kafka-client.properties.sasl.mechanism                        | WSPROXY_KAFKA_SASL_MECHANISM             |   not set   |
| kafka.ws.proxy.kafka-client.properties.sasl.jaas.config                      | WSPROXY_KAFKA_SASL_JAAS_CFG              |   not set   |
| kafka.ws.proxy.kafka-client.properties.sasl.kerberos.service.name            | WSPROXY_KAFKA_SASL_KERBEROS_SERVICE_NAME |   not set   |
| kafka.ws.proxy.kafka-client.properties.ssl.key.password                      | WSPROXY_KAFKA_SSL_KEY_PASS               |   not set   |
| kafka.ws.proxy.kafka-client.properties.ssl.endpoint.identification.algorithm | WSPROXY_KAFKA_SASL_ENDPOINT_ID_ALOGO     |   not set   |
| kafka.ws.proxy.kafka-client.properties.ssl.truststore.location               | WSPROXY_KAFKA_SSL_TRUSTSTORE_LOCATION    |   not set   |
| kafka.ws.proxy.kafka-client.properties.ssl.truststore.truststore.password    | WSPROXY_KAFKA_SSL_TRUSTSTORE_PASS        |   not set   |
| kafka.ws.proxy.kafka-client.properties.ssl.keystore.location                 | WSPROXY_KAFKA_SSL_KEYSTORE_LOCATION      |   not set   |
| kafka.ws.proxy.kafka-client.properties.ssl.keystore.password                 | WSPROXY_KAFKA_SSL_KEYSTORE_PASS          |   not set   |
| kafka.ws.proxy.kafka-client.properties.ssl.provider                          | WSPROXY_KAFKA_SSL_PROVIDER               |   not set   |
| kafka.ws.proxy.kafka-client.properties.ssl.cipher.suites                     | WSPROXY_KAFKA_SSL_CIPHER_SUITES          |   not set   |
| kafka.ws.proxy.kafka-client.properties.ssl.enabled.protocols                 | WSPROXY_KAFKA_SSL_ENABLED_PROTOCOLS      |   not set   |

Additionally, each of the different clients (admin, producer and consumer), can
be configured individually. However, _these configurations are not currently
exposed as environment variables_.

> **Info**
> 
> Please [open a new ticket here](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues) if your project
> require the individual Kafka client type configs to be exposed as environment variables.

The client specific configuration keys have the same structure as the
`kafka.ws.proxy.kafka-client.properties` key:

* `kafka.ws.proxy.admin-client.kafka-client-properties`
* `kafka.ws.proxy.consumer.kafka-client-properties`
* `kafka.ws.proxy.producer.kafka-client-properties`

### Kafka cluster with authorization restrictions (ACL)

For optimal operations the following permissions should be given to the
**principal** used by the Kafka WebSocket Proxy:

| Operation        | Resource | Feature                | Required | Description                                                                                                                                                                                                                                                                                                                                                  |
|:-----------------|:---------|:-----------------------|:--------:|:-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| DESCRIBE         | Cluster  | Health check           |   Yes    | Used to query the cluster state                                                                                                                                                                                                                                                                                                                              |
| DESCRIBE_CONFIGS | Cluster  | Health check           |   Yes    | Used to query the cluster state                                                                                                                                                                                                                                                                                                                              |
| DESCRIBE         | Topic    | WebSocket              |   Yes    | Used to calculate maximum number of websocket consumers a client can initiate                                                                                                                                                                                                                                                                                |
| DESCRIBE_CONFIGS | Topic    | WebSocket              |   Yes    | Used to calculate maximum number of websocket consumers a client can initiate                                                                                                                                                                                                                                                                                |
| DESCRIBE         | Group    | Admin API              |    No    | Required if the admin APIs are enabled, and being able to fetch consumer group information, etc. should be allowed.                                                                                                                                                                                                                                          |
| READ             | Group    | Admin API              |    No    | Required if the admin APIs are enabled, and being able to alter the offsets, etc. for a consumer group, should be allowed.                                                                                                                                                                                                                                   |
| DELETE           | Group    | Admin API              |    No    | Required if the admin APIs are enabled, and being able to delete a consumer group should be allowed.                                                                                                                                                                                                                                                         |
| CREATE           | Topic    | WebSocket + Admin API  |    No    | If not allowed, the `kafka.ws.proxy.session-handler.topic-name` and `kafka.ws.proxy.dynamic-config-handler.topic-name` topics must be created manually before starting the proxy                                                                                                                                                                             |
| READ             | Topic    | WebSocket + Admin API  |   Yes    | Can be restricted to `kafka.ws.proxy.session-handler.topic-name` (defaults to `_wsproxy.session.state`), `kafka.ws.proxy.dynamic-config-handler.topic-name` (defaults to `_wsproxy.dynamic.configs`), and `kafka.ws.proxy.kafka-client.confluent-monitoring.properties.interceptor.topic` (defaults to `_confluent-metrics`) if confluent metrics is enabled |
| WRITE            | Topic    | WebSocket + Admin API  |   Yes    | Can be restricted to `kafka.ws.proxy.session-handler.topic-name` (defaults to `_wsproxy.session.state`), `kafka.ws.proxy.dynamic-config-handler.topic-name` (defaults to `_wsproxy.dynamic.configs`), and `kafka.ws.proxy.kafka-client.confluent-monitoring.properties.interceptor.topic` (defaults to `_confluent-metrics`) if confluent metrics is enabled |

For more details on authorizations against the Kafka APIs, please see the official Kafka [documentation](https://kafka.apache.org/documentation/#operations_resources_and_protocols).
Or have a look at the [Confluent documentation](https://docs.confluent.io/platform/current/kafka/authorization.html#operations) for a more concise version.

## Confluent Metrics Reporter

If the property `kafka.ws.proxy.kafka-client.monitoring-enabled` is set to `true`,
the proxy service can be configured to send metrics data to a different cluster.
The cluster can be differently configured, and it is therefore necessary to
provide a distinct client configuration for the metrics reporter.


| Config key                                                                                        | Environment                                         |                        Default                        |
|:--------------------------------------------------------------------------------------------------|:----------------------------------------------------|:-----------------------------------------------------:|
| kafka.ws.proxy.kafka-client.confluent-monitoring.bootstrap-hosts                                  | WSPROXY_KAFKA_MONITORING_BOOTSTRAP_HOSTS            | same as `kafka.ws.proxy.kafka-client.bootstrap-hosts` |
| kafka.ws.proxy.kafka-client.confluent-monitoring.properties.interceptor.topic                     | WSPROXY_KAFKA_MONITORING_INTERCEPTOR_TOPIC          |                 `_confluent-metrics`                  |
| kafka.ws.proxy.kafka-client.confluent-monitoring.properties.security.protocol                     | WSPROXY_KAFKA_MONITORING_SECURITY_PROTOCOL          |                      `PLAINTEXT`                      |
| kafka.ws.proxy.kafka-client.confluent-monitoring.properties.sasl.mechanism                        | WSPROXY_KAFKA_MONITORING_SASL_MECHANISM             |                        not set                        |
| kafka.ws.proxy.kafka-client.confluent-monitoring.properties.sasl.jaas.config                      | WSPROXY_KAFKA_MONITORING_SASL_JAAS_CFG              |                        not set                        |
| kafka.ws.proxy.kafka-client.confluent-monitoring.properties.sasl.kerberos.service.name            | WSPROXY_KAFKA_MONITORING_SASL_KERBEROS_SERVICE_NAME |                        not set                        |
| kafka.ws.proxy.kafka-client.confluent-monitoring.properties.ssl.key.password                      | WSPROXY_KAFKA_MONITORING_SSL_KEY_PASS               |                        not set                        |
| kafka.ws.proxy.kafka-client.confluent-monitoring.properties.ssl.endpoint.identification.algorithm | WSPROXY_KAFKA_MONITORING_SASL_ENDPOINT_ID_ALOGO     |                        not set                        |
| kafka.ws.proxy.kafka-client.confluent-monitoring.properties.ssl.truststore.location               | WSPROXY_KAFKA_MONITORING_SSL_TRUSTSTORE_LOCATION    |                        not set                        |
| kafka.ws.proxy.kafka-client.confluent-monitoring.properties.ssl.truststore.truststore.password    | WSPROXY_KAFKA_MONITORING_SSL_TRUSTSTORE_PASS        |                        not set                        |
| kafka.ws.proxy.kafka-client.confluent-monitoring.properties.ssl.keystore.location                 | WSPROXY_KAFKA_MONITORING_SSL_KEYSTORE_LOCATION      |                        not set                        |
| kafka.ws.proxy.kafka-client.confluent-monitoring.properties.ssl.keystore.password                 | WSPROXY_KAFKA_MONITORING_SSL_KEYSTORE_PASS          |                        not set                        |
| kafka.ws.proxy.kafka-client.confluent-monitoring.properties.ssl.provider                          | WSPROXY_KAFKA_MONITORING_SSL_PROVIDER               |                        not set                        |
| kafka.ws.proxy.kafka-client.confluent-monitoring.properties.ssl.cipher.suites                     | WSPROXY_KAFKA_MONITORING_SSL_CIPHER_SUITES          |                        not set                        |
| kafka.ws.proxy.kafka-client.confluent-monitoring.properties.ssl.enabled.protocols                 | WSPROXY_KAFKA_MONITORING_SSL_ENABLED_PROTOCOLS      |                        not set                        |
