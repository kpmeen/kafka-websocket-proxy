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

TBD...

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

| Config key                                   | Type            | Environment                        | Default | Description |
|:----------                                   |:-----------     |:-----------                        |:-------:|:----------- |
| `kafka.websocket.proxy.kafka-bootstrap-urls` | array\[string\] | `KAFKA_BOOTSTRAP_URLS`             |         | An array of strings with URLs to the Kafka brokers in the form `<host>:<port>` |
| `kafka.websocket.proxy.default-rate-limit`   | number          | `KAFKA_WSPROXY_DEFAULT_RATE_LIMIT` |   `0`   | The maximum allowed throughput of data through a socket in bytes/second. A value of `0` means unlimited throughput. |
| `kafka.websocket.proxy.default-batch-size`   | number          | `KAFKA_WSPROXY_DEFAULT_BATCH_SIZE` |   `0`   | The number of messages to include per batch when consuming data. This property has no meaning without rate limiting turned on. A value of `0` means there will be no batching. |
|                                              |                 |                                    |         |             |


## Endpoints and API

All endpoints are available at the following base URL:

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

TBD...

##### Output

TBD...


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

##### Input

TBD...

##### Output

TBD...

# Development

## Testing

Using a CLI tool called `wscat`, interacting with the `kafka-websocket-proxy` is
relatively simple. The tool is freely available on
[github](https://github.com/websockets/wscat), and is highly recommended.

```bash
npm install -g wscat
```


