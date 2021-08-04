---
id: development
title: Development
slug: /development
---

## Development

tbd...

## Testing

### Connecting with `wscat`

Using a CLI tool called `wscat`, interacting with the `kafka-websocket-proxy` is
relatively simple. The tool is freely available on
[github](https://github.com/websockets/wscat), and is highly recommended.

1. Install `wscat`
   ```bash
   npm install -g wscat
   ```

2. Check `kafka-websocket-proxy` health
   ```bash
   curl http://<host>:<port>/healthcheck
   ```

   Expected response:

   ```json
   { "response": "I'm healthy" }
   ```

   If you receive an error response, verify the `kafka-websocket-proxy` config
   before going forward.

3. Open a consumer connection to `kafka-websocket-proxy`
   > NOTE: Ensure that there is an available topic to consume from in the Kafka cluster.

   ```bash
   wscat --connect "ws://<host>:<port>/socket/out?clientId=my-client&topic=my-topic&valType=json"
   Connected (press CTRL+C to quit)
   ```

3. Open a producer connection to `kafka-websocket-proxy`
   ```bash
   wscat --connect "ws://<host>:<port>/socket/in?clientId=my-client&topic=my-topic&valType=json"
   Connected (press CTRL+C to quit)
   >
   ```
   
4. Send a message through the producer socket
   ```json
   { "headers": [ { "key": "my_header", "value": "header_value" } ], "key": { "value": "foo", "format": "string" }, "value": { "value": "bar01", "format": "string" }, "messageId": "client_generated_id" }
   ```
   
