---
id: faq
title: FAQ
slug: /faq
---

## FAQ

##### My consumer client fails to reconnect because the `clientId` is in use

If the WebSocket client gets disconnected, and your code attempts to reconnect
automatically, you might experience that the connection is rejected due to the
`clientId` already being used. This is typically a transient state. `clientId`s
are used to identify a specific client, and is used to manage some internal
state related to this `clientId`. Whenever a consumer client disconnects, the
`kafka-websocket-proxy` needs to perform some cleanup related to this internal
`clientId` state. This can in some cases take a few milliseconds longer than
desired. CPU usage, memory availability, GC cycles, etc are all things
that can affect the cleanup duration.

To ensure your client is able to use the same `clientId` it is recommended to
wait a couple of seconds before attempting to reconnect the client.



