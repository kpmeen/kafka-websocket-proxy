---
id: apis
title: Endpoints and API 
slug: /apis
---

## WebSocket API

All WebSocket endpoints are available at the following base URL:

```
ws://<hostname>:<port>/socket
```

### Format types

The below table shows which data types are allowed as both key and value in
both inbound and outbound messages.

| Value     | Serde      | JSON type   |
|:--------- |:-----------|:----------- |
| json      | string     | json        |
| string    | string     | string      |
| int       | int        | number      |
| short     | short      | number      |
| long      | long       | number      |
| double    | double     | number      |
| float     | float      | number      |


### Payload types

> **INFO**
>
> Previous versions of Kafka WebSocket Proxy had support for using Avro as the wire protocol between proxy and clients.
> However, the implementation was cumbersome and is currently removed. There is a plan to re-add avro support again,
> with a much more sensible design.
> 
> Currently, however, Kafka WebSocket proxy only supports using JSON as the wire protocol between proxy and client.

| Value     | description                                                                                                 |
|:--------- |:----------------------------------------------------------------------------------------------------------- |
| json      | The default payload type to send via the WebSockets. See the endpoint docs for examples for JSON messages   |

### `/in`

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

| Name          | Type                           | Required | Default value | Description                                                                                                                                                                                                                 |
|:--------------|:-------------------------------|:--------:|:--------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| clientId      | string                         |    y     |               | For the `in` endpoint, the `clientId` parameter is used to group 1 or more `in` (producer) connections into one group that identifies the client application. Much like `groupId` is used for Kafka consumers.              |
| instanceId    | string                         |    n     |               | When more than one connection is made using the same `clientId`, and `kafka.ws.proxy.producer.sessions-enabled` is `true`, the `instanceId` is **required** and is used to differentiate between the different connections. |
| topic         | string                         |    y     |               | The topic name to write data into.                                                                                                                                                                                          |
| socketPayload | [payload type](#payload-types) |    n     | `json`        | The type of payload being sent via the proxy.                                                                                                                                                                               |
| keyType       | [format type](#format-types)   |    n     |               |                                                                                                                                                                                                                             |
| valType       | [format type](#format-types)   |    y     | `string`      |                                                                                                                                                                                                                             |
| transactional | boolean                        |    n     | `false`       | Used to enable "exactly once" semantics on the Kafka producer client used for the connection. Requires `exactly-once-enabled` to be `true` in the `application.conf`.                                                       | 

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

### `/out`

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

| Name                | Type                           | Required | Default value      | Description                                                                                                               |
|:--------------------|:-------------------------------|:--------:|:-------------------|---------------------------------------------------------------------------------------------------------------------------|
| clientId            | string                         |    y     |                    | Identifies a single client connection / Kafka consumer instance.                                                          |
| groupId             | string                         |    n     |                    | Id used to group all connections/clients into one logical instance that will consume different partitions from the topic. |
| topic               | string                         |    y     |                    | The topic name to write data into.                                                                                        |
| socketPayload       | [payload type](#payload-types) |    n     | `json`             | The type of payload being sent via the proxy.                                                                             |
| keyType             | [format type](#format-types)   |    n     |                    |                                                                                                                           |
| valType             | [format type](#format-types)   |    n     | `string`           |                                                                                                                           |
| offsetResetStrategy | string                         |    n     | `earliest`         |                                                                                                                           |
| isolationLevel      | string                         |    n     | `read_uncommitted` |                                                                                                                           |
| rate                | integer                        |    n     |                    |                                                                                                                           |
| batchSize           | integer                        |    n     |                    |                                                                                                                           |
| autoCommit          | boolean                        |    n     | `true`             |                                                                                                                           |

> **Note:**
> 
> `isolationLevel` values are the same as defined for the regular Kafka client.
> Please see [here](https://docs.confluent.io/platform/current/installation/configuration/consumer-configs.html#consumerconfigs_isolation.level) for more details.

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

## HTTP API

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


### GET `/healthcheck`

Simple endpoint to see if the service is up and running. It _does not_ verify
connectivity with the Kafka brokers. If this is necessary, use the
`/kafka/cluster/info` endpoint.

##### Output

```json
{ "response": "I'm healthy" }
```

## Admin Server HTTP API

### GET `/admin/kafka/info`

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

### GET `/admin/client/config`

By default, this endpoint will return all static client configurations for all known clients. Static
configurations are defined in the config file when starting the Kafka WebSocket Proxy.

When `kafka.ws.proxy.dynamic-config-handler.enabled` is set to `true`, this endpoint will also include
any client configurations that are dynamically set through the `PUT /admin/client/config/consumer/<consumer groupId>`
or `PUT /admin/client/config/producer/<producer clientId>`.

##### Output

```json
{
  "consumers" : {
    "static" : [
      {
        "batch-size" : 0,
        "group-id" : "__DEFAULT__",
        "max-connections" : 0,
        "messages-per-second" : 0
      },
      {
        "group-id" : "consumer-group-2",
        "max-connections" : 2,
        "messages-per-second" : 10
      }
    ],
    "dynamic" : [
      {
        "group-id" : "consumer-group-1",
        "max-connections" : 4,
        "messages-per-second" : 400
      }
    ]
  },
  "producers" : {
    "static" : [
      {
        "max-connections" : 0,
        "messages-per-second" : 0,
        "producer-id" : "__DEFAULT__"
      },
      {
        "max-connections" : 1,
        "messages-per-second" : 10,
        "producer-id" : "producer-1"
      }
    ],
    "dynamic" : [
      {
        "max-connections" : 4,
        "messages-per-second" : 400,
        "producer-id" : "producer-2"
      }
    ]
  }
}
```

### DELETE `/admin/client/config`

WARNING: With great power comes great responsibility! This endpoint will delete _ALL_ dynamic configurations that
have been added through the endpoints below.

Any statically defined configurations will remain untouched.

##### Output

* Returns HTTP `200` when successfully deleted.


### GET `/admin/client/config/consumer/<consumer groupId>`

Retrieves the proxy specific configuration that is being used for a given consumer group.

##### Output

```json
{
  "group-id": "test-consumer-2",
  "max-connections": 3,
  "messages-per-second": 300,
  "batch-size" : 100
}
```

### POST `/admin/client/config/consumer/<consumer groupId>`

Adds a proxy specific configuration to the given consumer group.
Note that the JSON structure to send as input must match the JSON being returned
from the `GET` endpoint described above.

##### Input

```json
{
  "group-id" : "test-consumer-2",
  "max-connections" : 5,
  "messages-per-second" : 300,
  "batch-size" : 100
}
```

##### Output

* Returns HTTP `200` when successfully added.
* Returns HTTP `404` if there is no such dynamic consumer group configuration.



### PUT `/admin/client/config/consumer/<consumer groupId>`

Updates the proxy specific configuration that is being used for a given consumer group.
Note that the JSON structure to send as input must match the JSON being returned
from the `GET` endpoint described above.

##### Input

```json
{
  "group-id" : "test-consumer-2",
  "max-connections" : 5,
  "messages-per-second" : 300,
  "batch-size" : 100
}
```

##### Output

* Returns HTTP `200` when successfully updated.
* Returns HTTP `404` if there is no such dynamic consumer group configuration.


### DELETE `/admin/client/config/consumer/<consumer groupId>`

Deletes the proxy specific _dynamic_ configuration that is being used for a given consumer group. If there is no
dynamic configuration set for the consumer group, nothing is deleted.

##### Output

* Returns HTTP `200` when successfully deleted.
* Returns HTTP `404` if there is no such dynamic consumer group configuration.


### GET `/admin/client/config/producer/<producer clientId>`

Retrieves the proxy specific configuration that is being used for producer instances with the given id.

##### Output

```json
{
  "max-connections" : 3,
  "messages-per-second" : 300,
  "producer-id" : "test-producer-2"
}
```


### POST `/admin/client/config/producer/<producer clientId>`

Adds a proxy specific configuration to all producer instances with the given producer client id.
Note that the JSON structure to send as input must match the JSON being returned
from the `GET` endpoint described above.

##### Input

```json
{
  "max-connections" : 3,
  "messages-per-second" : 300,
  "producer-id" : "test-producer-2"
}
```

##### Output

* Returns HTTP `200` when successfully deleted.
* Returns HTTP `404` if there is no such dynamic producer configuration.


### PUT `/admin/client/config/producer/<producer clientId>`

Updates the proxy specific configuration that is being used for producer instances with the given id.
Note that the JSON structure to send as input must match the JSON being returned
from the `GET` endpoint described above.

##### Input

```json
{
  "max-connections" : 3,
  "messages-per-second" : 300,
  "producer-id" : "test-producer-2"
}
```

##### Output

* Returns HTTP `200` when successfully deleted.
* Returns HTTP `404` if there is no such dynamic producer configuration.


### DELETE `/admin/client/config/producer/<producer clientId>`

Deletes the proxy specific _dynamic_ configuration that is being used for producer instances with the given id.
If there is no dynamic configuration set for the producer, nothing is deleted.

##### Output

* Returns HTTP `200` when successfully deleted.
* Returns HTTP `404` if there is no such dynamic producer configuration.

