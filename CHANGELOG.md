# Changelog

<!-- <START NEW CHANGELOG ENTRY> -->

## v2.0.0 (2024-04-06)


### Closed Issues

#### Improvements made

- Disable support for current Avro / binary implementation. [#116](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/116) ([@kpmeen](https://gitlab.com/kpmeen))
- Improve error handling during system initialisation [#114](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/114) ([@kpmeen](https://gitlab.com/kpmeen))
- Add metric for number of uncommitted messages on ConsumerClientStatsMXBean [#71](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/71) ([@kpmeen](https://gitlab.com/kpmeen))

#### Dependency updates

- Migrating from Akka to Pekko [#115](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/115) ([@kpmeen](https://gitlab.com/kpmeen))

#### Changes to the application runtime

- Build docker image for ARM chipset [#119](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/119) ([@kpmeen](https://gitlab.com/kpmeen))

#### Documentation improvements

- Fix outdated docs for admin endpoints [#118](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/118) ([@kpmeen](https://gitlab.com/kpmeen))

#### Test related changes

- Find a way to speed up some of the integration tests [#108](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/108) ([@kpmeen](https://gitlab.com/kpmeen))

#### Contributors to this release

[@kpmeen](https://gitlab.com/kpmeen)

## v1.2.0 (2023-03-09)


#### Contributors to this release


## v1.1.3 (2022-09-20)


### Closed Issues

#### New features added

- Add support for Kafka Consumer read isolation level [#83](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/83) ([@kpmeen](https://gitlab.com/kpmeen))
- Expose dedicated admin endpoints on separate port [#70](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/70) ([@kpmeen](https://gitlab.com/kpmeen))
- Dynamically configurable client specific producer rate-limiting [#66](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/66) ([@kpmeen](https://gitlab.com/kpmeen))

#### Bugs fixed

- Healthcheck not possible due to curl not being available in image/container [#113](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/113) ([@kpmeen](https://gitlab.com/kpmeen), [@ex-ratt](https://gitlab.com/ex-ratt))
- Crash due to growing number of admin client threads (WsKafkaAdminClient not properly closed) [#112](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/112) ([@kpmeen](https://gitlab.com/kpmeen), [@ex-ratt](https://gitlab.com/ex-ratt))
- Exception in main thread does not kill application [#111](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/111) ([@kpmeen](https://gitlab.com/kpmeen), [@ex-ratt](https://gitlab.com/ex-ratt))

#### CI changes

- Fix broken GitLab CI dependency scanning [#80](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/80) ([@kpmeen](https://gitlab.com/kpmeen))

#### Unlabelled Closed Issues

- 400 returned when using wscat to test [#110](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/110) ([@jamesbmorris92](https://gitlab.com/jamesbmorris92), [@kpmeen](https://gitlab.com/kpmeen))
- Instance Exists - cannot connect with more than one client [#107](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/107) ([@chris-aeviator](https://gitlab.com/chris-aeviator), [@kpmeen](https://gitlab.com/kpmeen))
- Add akka-cluster to support horisontal scaling [#45](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/45) ([@kpmeen](https://gitlab.com/kpmeen))

#### Contributors to this release

[@ex-ratt](https://gitlab.com/ex-ratt) | [@kpmeen](https://gitlab.com/kpmeen)

## v1.1.2 (2022-05-02)


### Closed Issues

#### Bugs fixed

- Logical error in producer session prevents any client from having more than 1 connection [#105](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/105) ([@kpmeen](https://gitlab.com/kpmeen))

#### Contributors to this release

[@kpmeen](https://gitlab.com/kpmeen)

## v1.1.1 (2022-03-01)


### Closed Issues

#### Bugs fixed

- Disable producer session implementation [#106](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/106) ([@kpmeen](https://gitlab.com/kpmeen))

#### Dependency updates

- Upgrade to Java 17 LTS [#104](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/104) ([@kpmeen](https://gitlab.com/kpmeen))

#### Contributors to this release

[@kpmeen](https://gitlab.com/kpmeen)

## v1.1.0 (2022-01-31)


### Closed Issues

#### Security related issues

- Add support for using  Scram login with external clients. [#103](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/103) ([@kpmeen](https://gitlab.com/kpmeen))

#### Unlabelled Closed Issues

- 500 Cannot access session value when it's not found [#102](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/102) ([@chris-aeviator](https://gitlab.com/chris-aeviator), [@kpmeen](https://gitlab.com/kpmeen))

#### Contributors to this release

[@kpmeen](https://gitlab.com/kpmeen)

## v1.0.0 (2021-12-02)


#### Contributors to this release


## v0.6.2 (2021-09-22)


### Closed Issues

#### Improvements made

- Should be possible to set loggers to use JSON format using an environment variable [#88](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/88) ([@kpmeen](https://gitlab.com/kpmeen))

#### Bugs fixed

- Resource leakage in proxy when connections terminate [#92](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/92) ([@kpmeen](https://gitlab.com/kpmeen))

#### Documentation improvements

- Add some simple examples on how to use wscat to test the websocket endpoints [#86](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/86) ([@james.regis](https://gitlab.com/james.regis), [@kpmeen](https://gitlab.com/kpmeen))

#### Unlabelled Closed Issues

- local complication does not work [#93](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/93) ([@abhinav-jain09](https://gitlab.com/abhinav-jain09), [@kpmeen](https://gitlab.com/kpmeen))
- Sometimes the connection on /out enpoint fails due to clientID [#87](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/87) ([@kpmeen](https://gitlab.com/kpmeen), [@james.regis](https://gitlab.com/james.regis))
- How to test kafka-websocket-proxy [#85](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/85) ([@kpmeen](https://gitlab.com/kpmeen), [@james.regis](https://gitlab.com/james.regis))

#### Contributors to this release

[@james.regis](https://gitlab.com/james.regis) | [@kpmeen](https://gitlab.com/kpmeen)

## v0.6.1 (2021-04-23)


### Closed Issues

#### Improvements made

- Log error message when credentials cannot be found in JWT token [#84](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/84) ([@kpmeen](https://gitlab.com/kpmeen))

#### Documentation improvements

- Add sbt-mdoc plugin to generate pretty documentation with references to version controlled code [#72](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/72) ([@kpmeen](https://gitlab.com/kpmeen))
- Improve documentation! [#29](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/29) ([@kpmeen](https://gitlab.com/kpmeen))

#### Contributors to this release


## v0.6.0 (2021-03-19)


#### Contributors to this release


## v0.5.4 (2021-03-08)


### Closed Issues

#### New features added

- Add config flag to explicitly allow logging of a more dubious nature [#78](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/78) ([@kpmeen](https://gitlab.com/kpmeen))

#### Improvements made

- Improve JWT logging [#81](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/81) ([@kpmeen](https://gitlab.com/kpmeen))
- Add configurable parameters to control Kafka producer retry and timeout behaviour [#77](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/77) ([@kpmeen](https://gitlab.com/kpmeen))

#### Security related issues

- Add config to allow only accepting Kafka creds through JWT token [#76](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/76) ([@kpmeen](https://gitlab.com/kpmeen))

#### Contributors to this release

[@kpmeen](https://gitlab.com/kpmeen)

## v0.5.3 (2021-02-24)


### Closed Issues

#### Security related issues

- Support providing Kafka credentials through the OIDC JWT token. [#75](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/75) ([@kpmeen](https://gitlab.com/kpmeen))

#### Contributors to this release

[@kpmeen](https://gitlab.com/kpmeen)

## v0.5.2 (2020-12-14)


### Closed Issues

#### Bugs fixed

- The monitoring actors cause a 500 internal server error when trying to start while a client is producing messages. [#74](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/74) ([@kpmeen](https://gitlab.com/kpmeen))

#### Contributors to this release

[@kpmeen](https://gitlab.com/kpmeen)

## v0.5.1 (2020-11-30)


### Closed Issues

#### Improvements made

- Add option to turn off coloured log output [#69](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/69) ([@kpmeen](https://gitlab.com/kpmeen))

#### Bugs fixed

- Proxy server seems to stall when/if the OIDC connection fails [#73](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/73) ([@kpmeen](https://gitlab.com/kpmeen))

#### Contributors to this release

[@kpmeen](https://gitlab.com/kpmeen)

## v0.5.0 (2020-11-16)


### Closed Issues

#### New features added

- Expose proxy metrics via JMX [#67](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/67) ([@kpmeen](https://gitlab.com/kpmeen))

#### Contributors to this release

[@kpmeen](https://gitlab.com/kpmeen)

## v0.4.2 (2020-10-20)


### Closed Issues

#### Bugs fixed

- Kafka clients falls back to default security when SASL is enabled and Kafka credentials are not set in websocket request header [#68](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/68) ([@kpmeen](https://gitlab.com/kpmeen))

#### Documentation improvements

- Add section on creating session state topic manually [#65](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/65) ([@kpmeen](https://gitlab.com/kpmeen))

#### Unlabelled Closed Issues

- Support rate-limiting of producer messages [#63](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/63) ([@kpmeen](https://gitlab.com/kpmeen))

#### Contributors to this release

[@kpmeen](https://gitlab.com/kpmeen)

## v0.4.0 (2020-10-08)


### Closed Issues

#### Improvements made

- Add support for optional message identifier, set by the client side, when producing messages [#62](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/62) ([@kpmeen](https://gitlab.com/kpmeen))
- Ensure session handler and openid client are singletons [#60](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/60) ([@kpmeen](https://gitlab.com/kpmeen))

#### Bugs fixed

- Headers are not applied to produced messages [#64](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/64) ([@kpmeen](https://gitlab.com/kpmeen))

#### Contributors to this release

[@kpmeen](https://gitlab.com/kpmeen)

## v0.3.0 (2020-08-22)


### Closed Issues

#### Security related issues

- Implement support for OpenID/OAuth [#58](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/58) ([@kpmeen](https://gitlab.com/kpmeen))
- Implement support for Basic Auth [#57](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/57) ([@kpmeen](https://gitlab.com/kpmeen))

#### Unlabelled Closed Issues

- Investigate vulnerability: Potential CRLF Injection for logs [#59](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/59) ([@kpmeen](https://gitlab.com/kpmeen))

#### Contributors to this release

[@kpmeen](https://gitlab.com/kpmeen)

## v0.2.0 (2020-08-05)


### Closed Issues

#### New features added

- Add config listener to allow setting log-levels for logback loggers as environment variables. [#50](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/50) ([@kpmeen](https://gitlab.com/kpmeen))
- Add support for ACL credentials per consumer when setting up a WebSocket [#38](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/38) ([@kpmeen](https://gitlab.com/kpmeen))
- Add support for Kafka message headers. [#37](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/37) ([@kpmeen](https://gitlab.com/kpmeen))
- Implement endpoint for consuming raw binary data, without any wrapper message [#36](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/36) ([@kpmeen](https://gitlab.com/kpmeen))
- Implement passthrough endpoint for producing raw binary data, without any wrapper message [#35](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/35) ([@kpmeen](https://gitlab.com/kpmeen))
- Implement endpoint to verify Kafka cluster connectivity using the bootstrap-urls configuration. [#33](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/33) ([@kpmeen](https://gitlab.com/kpmeen))
- Make Confluence Metrics Reporter configurable [#27](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/27) ([@kpmeen](https://gitlab.com/kpmeen))
- Add support for sharing information across instances [#19](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/19) ([@kpmeen](https://gitlab.com/kpmeen))
- Limit number of consumers for a given group.id [#16](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/16) ([@kpmeen](https://gitlab.com/kpmeen))
- Support sending raw Avro messages [#15](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/15) ([@MichaelHussey](https://gitlab.com/MichaelHussey), [@kpmeen](https://gitlab.com/kpmeen))
- Application must read configuration from application.conf [#6](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/6) ([@kpmeen](https://gitlab.com/kpmeen))
- Use JSON format for inbound and outbound messages [#5](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/5) ([@kpmeen](https://gitlab.com/kpmeen))
- Separate producer and consumer into separate endpoints. [#3](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/3) ([@kpmeen](https://gitlab.com/kpmeen))
- It should be possible to commit message offsets for topics through the inbound socket. [#2](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/2) ([@kpmeen](https://gitlab.com/kpmeen))
- Support multiple consumers per consumer group [#1](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/1) ([@kpmeen](https://gitlab.com/kpmeen))

#### Improvements made

- Refactor CommitHandler and CommitStackTypes to use non-deprecated implementation for committing Kafka messages [#56](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/56) ([@kpmeen](https://gitlab.com/kpmeen))
- Binary data in AvroProducerRecord and AvroConsumerRecord protocol message should be validated against the schema registry [#55](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/55) ([@kpmeen](https://gitlab.com/kpmeen))
- Do not use schema-registry for Avro protocol schemas [#54](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/54) ([@kpmeen](https://gitlab.com/kpmeen))
- Allow endpoints for Avro to specify a non-avro based key and value [#53](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/53) ([@kpmeen](https://gitlab.com/kpmeen))
- Refactor the Avro endpoints for binary data [#52](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/52) ([@kpmeen](https://gitlab.com/kpmeen))
- Implement custom ContextListener for the Logback logging framework to pick up logger configs as JVM arguments [#51](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/51) ([@kpmeen](https://gitlab.com/kpmeen))
- Improve resiliency while bootstrapping [#48](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/48) ([@kpmeen](https://gitlab.com/kpmeen))
- Add HEALTHCHECK to Docker image [#47](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/47) ([@kpmeen](https://gitlab.com/kpmeen))
- Upgrade app and sbt plugins dependencies to latest versions [#42](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/42) ([@kpmeen](https://gitlab.com/kpmeen))
- Add excplicit value types for frequently used attributes to improve code and APIs [#28](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/28) ([@kpmeen](https://gitlab.com/kpmeen))
- Replace StdIn.readLine with proper shutdown hooks [#25](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/25) ([@kpmeen](https://gitlab.com/kpmeen))
- Improve logging [#24](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/24) ([@kpmeen](https://gitlab.com/kpmeen))
- kafka.ws.proxy.server.server-id should be a String value [#22](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/22) ([@kpmeen](https://gitlab.com/kpmeen))

#### Bugs fixed

- When WSPROXY_SCHEMA_REGISTRY_URL env variable is not set, the configuration defaults to use localhost:28081 [#49](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/49) ([@kpmeen](https://gitlab.com/kpmeen))
- Failed client requests aren't properly cleaned up from session handler [#40](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/40) ([@kpmeen](https://gitlab.com/kpmeen))
- Capture error message when topic is not found and return HTTP 400 [#39](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/39) ([@kpmeen](https://gitlab.com/kpmeen))
- Implement bootstrapping check to validate that the kafka bootstrap hosts are resolvable [#34](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/34) ([@kpmeen](https://gitlab.com/kpmeen))
- Commit handler must safely deal with uncommitted messages across partitions [#12](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/12) ([@kpmeen](https://gitlab.com/kpmeen))

#### Changes to the application runtime

- Publish docker images to docker hub [#21](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/21) ([@kpmeen](https://gitlab.com/kpmeen))
- Setup proper CI pipeline [#4](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/4) ([@kpmeen](https://gitlab.com/kpmeen))

#### Documentation improvements

- Document JSON messages [#14](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/14) ([@kpmeen](https://gitlab.com/kpmeen))

#### Test related changes

- Verify running proxy with security setup [#20](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/20) ([@kpmeen](https://gitlab.com/kpmeen))
- Implement simple consumer load/stress tests [#17](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/17) ([@kpmeen](https://gitlab.com/kpmeen))
- Add tests! [#10](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/10) ([@kpmeen](https://gitlab.com/kpmeen))

#### Security related issues

- Add option to configure JAAS through props instead of jaas config file [#41](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/41) ([@kpmeen](https://gitlab.com/kpmeen))
- Add TLS support for endpoints [#32](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/32) ([@kpmeen](https://gitlab.com/kpmeen))
- Make individual security properties configurable through environment variables - part 2 [#26](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/26) ([@kpmeen](https://gitlab.com/kpmeen))
- Make individual security properties configurable through environment variables [#23](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/23) ([@kpmeen](https://gitlab.com/kpmeen))

#### Unlabelled Closed Issues

- Add support for gRPC endpoints [#44](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/44) ([@kpmeen](https://gitlab.com/kpmeen))
- Support consuming from multiple topics via a single WebSocket connection [#43](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/43) ([@kpmeen](https://gitlab.com/kpmeen))
- Investigate how to terminate WebSocket from another process / actor [#31](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/31) ([@kpmeen](https://gitlab.com/kpmeen))
- Build docker image with pre-installed kafka-websocket-proxy [#13](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/13) ([@kpmeen](https://gitlab.com/kpmeen))
- Add check to prevent opening more sockets than topic partitions [#11](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/11) ([@kpmeen](https://gitlab.com/kpmeen))
- Align error handling and codes with REST proxy [#9](https://gitlab.com/kpmeen/kafka-websocket-proxy/-/issues/9) ([@kpmeen](https://gitlab.com/kpmeen))

#### Contributors to this release

[@MichaelHussey](https://gitlab.com/MichaelHussey) | [@kpmeen](https://gitlab.com/kpmeen)

<!-- <END NEW CHANGELOG ENTRY> -->