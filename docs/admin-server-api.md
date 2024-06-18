---
id: http
title: Admin Server API 
slug: /apis/admin
---

> **Important**
>
> Some of these endpoints leverage the Kafka Admin Client under the hood. For
> these to function properly, it is important that the Kafka user configured
> for the Kafka WebSocket Proxy in the SASL configuration for the Kafka client
> has the proper ACLs defined in Kafka.
>
> See [Kafka Security configuration](configuration.md#kafka-security) for
> documentation of security configurations against Kafka.

By default, the Admin Server API is _not_ enabled. Please see the [Admin Server Configuration](configuration.md#admin-server-configuration)
documentation for details on how this can be enabled.

### GET `/admin/kafka/info`

This is a convenience endpoint to verify that the service can access the brokers
in the cluster.

##### Output

```json5
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

```json5
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

```json5
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

```json5
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

```json5
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

```json5
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

```json5
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

```json5
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


---

### GET `/admin/consumer-group/all`

Will list all consumer groups available in the Kafka cluster. By default, consumer groups that are considered
internal are excluded from the listing. Internal groups are defined by naming convention, where the name
starts with an `_`, `ws-proxy-dynamic-config-handler-consumer` or `ws-proxy-session-consumer`.

By including the query parameter `includeInternals=true`, the internal groups will be included in the listing.

To only show the groups that are currently considered active, include the query parameter `activeOnly=true`. This
will only show groups where the current state is one of `STABLE`, `PREPARING_REBALANCE` or `COMPLETING_REBALANCE`.

**Query parameters**:

| Name             | Type    | Required | Default value | Description                                                                                                          |
|:-----------------|:--------|:--------:|:--------------|----------------------------------------------------------------------------------------------------------------------|
| activeOnly       | boolean |    n     | false         | When true, will only return consumer groups in these states: `STABLE`, `PREPARING_REBALANCE`, `COMPLETING_REBALANCE` |
| includeInternals | boolean |    n     | false         | When true, will include all consumer groups that are internal to Kafka WebSocket Proxy and Kafka.                    |

##### Output

Example where `activeOnly=false` and `includeInternals=true`

```json5
[
  {
    "groupId" : "group1",
    "isSimple" : false,
    "state" : "STABLE",
    "members" : [
    ]
  },
  {
    "groupId" : "ws-proxy-session-consumer-node-1",
    "isSimple" : false,
    "state" : "STABLE",
    "members" : [
    ]
  },
  {
    "groupId" : "group2",
    "isSimple" : false,
    "state" : "PREPARING_REBALANCE",
    "members" : [
    ]
  },
  {
    "groupId" : "ws-proxy-session-consumer-node-2",
    "isSimple" : true,
    "state" : "STABLE",
    "members" : [
    ]
  },
  {
    "groupId" : "group3",
    "isSimple" : false,
    "state" : "STABLE",
    "members" : [
    ]
  }
]
```


### GET `/admin/consumer-group/<consumer group id>/describe`

Show more details about a consumer group. This only includes the most relevant information available from
the underlying Kafka AdminClient API.

##### Output

* Returns HTTP `200` when successful.
* Returns HTTP `404` if the consumer group does not exist.

```json5
{
  "groupId" : "group1",
  "isSimple" : false,
  "state" : "STABLE",
  "partitionAssignor" : "range",
  "members" : [
    "my-active-client-1",
    "my-active-client-2"
  ]
}
```

### GET `/admin/consumer-group/<consumer group id>/offsets`

Find and return the current offsets for the given consumer group.

##### Output

* Returns HTTP `200` when successful.
* Returns HTTP `404` if the consumer group does not exist.

```json5
[
  {
    "topic" : "test-topic",
    "partition" : 2,
    "offset" : 1,
    "metadata" : null  // optional
  },
  {
    "topic" : "test-topic",
    "partition" : 1,
    "offset" : 1,
    "metadata" : null  // optional
  },
  {
    "topic" : "test-topic",
    "partition" : 0,
    "offset" : 1,
    "metadata" : null  // optional
  }
]
```


### DELETE `/admin/consumer-group/<consumer group id>/offsets`

Remove any known offsets for the given consumer group. This will effectively ensure that the
consumer group starts consuming from the earliest known point of the topic.

> **Important**:
>
> All consumers in the given consumer group _must be stopped_ for this endpoint to successfully delete the offsets.

##### Output

* Returns HTTP `200` when successfully deleted.
* Returns HTTP `404` if the consumer group does not exist.
* Returns HTTP `412` if the consumer group is still active.


### PUT `/admin/consumer-group/<consumer group id>/offsets/alter/<topic name>`

Alter the offsets for the given consumer group and topic. The endpoint provides similar functionality, and
guarantees of success as the underlying Kafka AdminClient API `alterConsumerGroupOffsets`.

This means two things:

1. In order to succeed, the consumer group must be empty (no active consumer client members).
2. The operation is not transactional. So it may succeed for some partitions while fail for others.

Unlike the underlying Kafka AdminClient, the endpoint will return the newly altered offsets when the
operation has completed. This allows the caller to verify the result of operation.

##### Input

```json5
[
  {
    "topic" : "test-topic",
    "partition" : 2,
    "offset" : 100,
    "metadata" : null  // optional
  },
  {
    "topic" : "test-topic",
    "partition" : 1,
    "offset" : 100,
    "metadata" : null  // optional
  },
  {
    "topic" : "test-topic",
    "partition" : 0,
    "offset" : 100,
    "metadata" : null  // optional
  }
]
```

##### Output

* Returns HTTP `200` when successfully altered.
* Returns HTTP `404` if the consumer group does not exist.
* Returns HTTP `412` if the consumer group is still active.

```json5
[
  {
    "topic" : "test-topic",
    "partition" : 2,
    "offset" : 100,
    "metadata" : null  // optional
  },
  {
    "topic" : "test-topic",
    "partition" : 1,
    "offset" : 100,
    "metadata" : null  // optional
  },
  {
    "topic" : "test-topic",
    "partition" : 0,
    "offset" : 100,
    "metadata" : null  // optional
  }
]
```
