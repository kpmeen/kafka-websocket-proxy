[![pipeline status](https://gitlab.com/kpmeen/kafka-websocket-proxy/badges/master/pipeline.svg)](https://gitlab.com/kpmeen/kafka-websocket-proxy/commits/master)
[![coverage report](https://gitlab.com/kpmeen/kafka-websocket-proxy/badges/master/coverage.svg)](https://gitlab.com/kpmeen/kafka-websocket-proxy/commits/master)

# Kafka WebSocket Proxy

Documentation is available at [https://kpmeen.gitlab.io/kafka-websocket-proxy/](https://kpmeen.gitlab.io/kafka-websocket-proxy/).

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

