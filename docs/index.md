---
id: index
title: Kafka WebSocket Proxy 
slug: /
keywords:
  - kafka
  - websockets
  - proxy
  - streaming
  - ingestion
  - consumption
---

The Kafka WebSocket Proxy is mainly created as a more efficient alternative to
the existing HTTP based REST proxy.

With WebSockets, the overhead of the HTTP protocol is removed. Instead, a much
"cheaper" TCP socket is setup between the client and proxy, through any
intermediate networking components.

Since WebSockets are bidirectional, they open up for client - server
implementations that are much closer to the regular Kafka client. The WebSocket
becomes more like an extension to the Kafka consumer/producer clients used
internally in the proxy.

> ### Important note on compatibility
>
> Prior to version 1.x.x the internal session model only supported consumers.
> This model is not compatible with the session model used in version 1.x.x.
>
> If Kafka WebSocket Proxy has only ever been used for producer clients, then
> the upgrade should be unproblematic.
>
> When Kafka WebSocket Proxy has been used for consuming data it is best to
> ensure that the session topic is empty / recreated on the Kafka cluster before
> deploying version 1.x.x. 
