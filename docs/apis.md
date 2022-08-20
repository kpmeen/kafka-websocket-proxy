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
| avro      | byte[]     | base64      |
| bytearray | byte[]     | base64      |
| string    | string     | string      |
| int       | int        | number      |
| short     | short      | number      |
| long      | long       | number      |
| double    | double     | number      |
| float     | float      | number      |


### Payload types

| Value     | description                                                                                                 |
|:--------- |:----------------------------------------------------------------------------------------------------------- |
| json      | The default payload type to send via the WebSockets. See the endpoint docs for examples for JSON messages   |
| avro      | Used when WebSocket payload uses the Avro format described in [Avro Payload Schemas](#avro-payload-schemas) |

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

| Name          | Type                           | Required | Default value | Description                                                                                                                                                                                                                |
|:--------------|:-------------------------------|:--------:|:--------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| clientId      | string                         |    y     |               | For the `in` endpoint, the `clientId` parameter is used to group 1 or more `in` (producer) connections into one group that identifies the client application. Much like `groupId` is used for Kafka consumers.             |
| instanceId    | string                         |    n     |               | When more than one connection is made using the same `clientId`, and `kafka.ws.proxy.producer.sessions-enabled` is `true`, the `instanceId` is **required** and is used to differentiate between the different connections. |
| topic         | string                         |    y     |               | The topic name to write data into.                                                                                                                                                                                         |
| socketPayload | [payload type](#payload-types) |    n     | `json`        | The type of payload being sent via the proxy.                                                                                                                                                                       |
| keyType       | [format type](#format-types)   |    n     |               |                                                                                                                                                                                                                            |
| valType       | [format type](#format-types)   |    y     | `string`      |                                                                                                                                                                                                                            |

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

### Avro payload schemas

An alternative to the default JSON payloads used by the WebSocket proxy, one can
use a set of predefined Avro schemas to send and receive data. Below are
endpoints where the schemas for these Avro _wrapper_ messages can be found.

### `/schemas/avro/producer/record`

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

### `/schemas/avro/producer/result`

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

### `/schemas/avro/consumer/record`

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

### `/schemas/avro/consumer/commit`

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

### `/kafka/cluster/info`

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

### `/healthcheck`

Simple endpoint to see if the service is up and running. It _does not_ verify
connectivity with the Kafka brokers. If this is necessary, use the
`/kafka/cluster/info` endpoint.

##### Output

```json
{ "response": "I'm healthy" }
```
