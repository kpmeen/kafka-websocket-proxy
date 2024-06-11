---
id: websockets
title: WebSockets endpoints and API 
slug: /apis/proxy
---

All WebSocket endpoints are available at the following base URL:

```
ws://<hostname>:<port>/socket
```

### Format types

The below table shows which data types are allowed as both key and value in
both inbound and outbound messages.

| Value     | Serde      | JSON type   |
|:----------|:-----------|:------------|
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

| Value      | description                                                                                                  |
|:-----------|:-------------------------------------------------------------------------------------------------------------|
| json       | The default payload type to send via the WebSockets. See the endpoint docs for examples for JSON messages    |

### `/in`

**Headers**:

| Name           | Type                               | Required | Description                                                                                                                                                    |
|:---------------|:-----------------------------------|:--------:|:---------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Authorization  | Basic authentication (Base64)      |     n    | [Basic authentication](https://en.wikipedia.org/wiki/Basic_access_authentication) header.                                                                      |
| X-Kafka-Auth   | Base64                             |     n    | Header for providing Base64 representation of credentials in the form `username:password` to use for the Kafka connection when the topic has ACL restrictions. |

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

```json55
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

```json55
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

| Name           | Type                               | Required | Description                                                                                                                                                            |
|:---------------|:-----------------------------------|:--------:|:-----------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Authorization  | Basic authentication (Base64)      |    n     | [Basic authentication](https://en.wikipedia.org/wiki/Basic_access_authentication) header.                                                                              |
| X-Kafka-Auth   | Base64                             |    n     | Header for providing Base64 representation of credentials in the form `username:password` to use for secure Kafka connections and when the topic has ACL restrictions. |

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

```json55
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

```json55
{
  "wsProxyMessageId": "foo-0-1-1554402266846"
}
```
