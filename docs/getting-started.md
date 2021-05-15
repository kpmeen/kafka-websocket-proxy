---
id: getting-started
title: Getting Started
slug: /getting-started
---

## Docker image

The `kafka-websocket-proxy` is available pre-installed in a docker image from
Docker Hub at [kpmeen/kafka-websocket-proxy](https://hub.docker.com/r/kpmeen/kafka-websocket-proxy/tags).

For configuration, please see [Configuration](configuration.md) for details on
which environment properties should be used.

### Healthcheck

To add a healthcheck to to the docker container, a command calling the
`/healthcheck` endpoint can be configured. Below is an example using
docker-compose:

```
services:
  ...

  kafka-ws-proxy:
    image: kpmeen/kafka-websocket-proxy:latest
    ...
    healthcheck:
      test: ['CMD-SHELL', 'curl -f http://localhost:8087/healthcheck || exit 1']
      interval: 30s
      timeout: 3s
      retries: 40
    ...

  ...
```
