---
id: faq
title: FAQ
slug: /faq
---

## FAQ

##### Can I use Kafka WebSocket Proxy from a frontend client?

In general, yes. But there are a couple of important things to consider first.

* A WebSocket is a long-lived connection. And if you open 1 connection per
  browser tab, you will have to use different `groupId` for each tab.

* Consider the amount of data being sent to your browser and how frequent each
  tab is re-rendering components based on the data. In a worst case scenario the
  browser performance will degrade to the point where it locks up completely.

* Consider using one or more Service Workers to connect to the WebSocket Proxy.
  This will limit the number of connections required within the same browser.


##### My consumer client fails to reconnect because the `clientId` is in use

If the WebSocket client gets disconnected, and your code attempts to reconnect
automatically, you might experience that the connection is rejected due to the
`clientId` already being used. This is typically a transient state. `clientId`s
are used to identify a specific client, and is used to manage some internal
state related to this `clientId`. Whenever a consumer client disconnects, the
`kafka-websocket-proxy` needs to perform some cleanup tasks related to this
internal `clientId` state. This can, in some cases, take a few milliseconds
longer than desired. CPU usage, memory availability, GC cycles, etc. are all
things that can affect the cleanup duration.

To ensure your client is able to use the same `clientId` it is recommended to
wait a couple of seconds before attempting to reconnect the client.

If the problem still persists, please don't hesitate opening a ticket in the
[issue tracker](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues) where
you describe the issue. Please be as detailed as possible to help us understand
your problem.


##### The websocket connection between my client and the proxy is terminating at a regular interval

If there is a firewall (like F5) between your client and the Kafka WebSocket Proxy, there may be
some rules in place that prevent a connection to be open for more than a given amount of time.
Usually this is because a set of rules for HTTP connections are triggered.  To address this issue,
it is necessary to ensure the firewall allows WebSocket connections, and that they are allowed to
remain open to prevent the firewall terminating the connection. 


