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
