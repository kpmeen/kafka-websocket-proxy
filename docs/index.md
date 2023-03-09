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
implementations that are much closer to the way the regular Kafka client works.
The WebSocket becomes more like an extension to the Kafka consumer/producer
clients used internally in the proxy.


> ### Caution
> Using the Kafka WebSocket Proxy as a way to feed data directly to browser
> based clients _can_ cause serious problems for the user experience when the
> data rates are high enough. It is therefore very important to consider how
> often and when your web application re-renders the page. In bad cases the
> web browser may end up freezing the computer.
>
> For these use-cases, consider having the WebSocket client receive data in web
> workers, specifically a `SharedWorker`. This allows sending the same data to
> each open tab containing the web application that needs a WebSocket connection.
> **See [this SO answer](https://stackoverflow.com/a/61866896) for more details on this technique.**
