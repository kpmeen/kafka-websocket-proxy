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
using HTTP based REST proxies.

With WebSockets, the overhead of the HTTP protocol is removed. Instead, a much
"cheaper" TCP socket is set up between the client and proxy, through any
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


> ### Known bug for version 1.0.0 and 1.1.0
> 
> Version 1.0.0 of the Kafka WebSocket Proxy introduced a new feature intended
> for limiting the number of connections a given producer client can set up.
> Unfortunately the implementation was not bullet-proof, and contained a bug
> that rendered the feature quite useless.
> 
> In releases after 1.0.0 and before 1.1.2 the feature was disabled entirely.
> From version 1.1.2 the feature has been reintroduced. But it is turned off
> by default. Please refer to the [Configuration](configuration.md) docs for
> details on how to enable it.
>
> If this feature is required for your use-case, please ensure you are using
> version >=1.1.2, and have read the feature documentation.