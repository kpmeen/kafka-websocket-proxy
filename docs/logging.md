---
id: logging
title: Logging
slug: /logging
---

The `kafka-websocket-proxy` uses [Logback](http://logback.qos.ch) for logging,
and comes pre-packaged with a configuration file with reasonable defaults. If
there is a need to use a different configuration, there are 3 recommended
alternatives.

## 1. Providing an external log configuration file

If it is necessary for any reason to use a different log configuration, the
most common way of doing so is to pass in a JVM argument when starting the
application.

The application accepts the standard Logback argument
`-Dlogback.configurationFile=<file_path>` to reference a different config file.

This argument can either be set explicitly in the `bin/server` that comes with
the distribution. Or, more easily, added to the `JAVA_OPTS` environment variable.

## 2. Overriding log levels for predefined loggers

It is possible to set the log levels of some important loggers through
environment variables. The below table shows which are available, and what their
default values are.

| Logger                            | Environment                      | Default |
|:---                               |:----                             |:-------:|
| akka.actor                        | WS_PROXY_AKKA_ACTOR_LOG_LEVEL    |  WARN   |
| akka.http                         | WS_PROXY_AKKA_HTTP_LOG_LEVEL     |  WARN   |
| akka.kafka                        | WS_PROXY_AKKA_KAFKA_LOG_LEVEL    |  WARN   |
| org.apache.kafka.clients          | WS_PROXY_KAFKA_CLIENTS_LOG_LEVEL |  ERROR  |
| net.scalytica.kafka.wsproxy       | WS_PROXY_APP_LOG_LEVEL           |  DEBUG  |
| net.scalytica.kafka.wsproxy.auth  | WS_PROXY_AUTH_LOG_LEVEL          |  DEBUG  |
| net.scalytica.kafka.wsproxy.admin | WS_PROXY_ADMIN_LOG_LEVEL         |  WARN   |
| root                              | WS_PROXY_ROOT_LOG_LEVEL          |  ERROR  |

## 3. Overriding full configuration through environment

Another option that is useful when running the application in a docker container,
or another environment where configuration is primarily done through
environment variables, is the environment variable `WSPROXY_LOGBACK_XML_CONFIG`.

When the `WSPROXY_LOGBACK_XML_CONFIG` variable has a value, all other log
configurations will be ignored. So if e.g. both `WSPROXY_LOGBACK_XML_CONFIG` and
`WS_PROXY_KAFKA_CLIENTS_LOG_LEVEL` are set, the latter will be ignored
completely. The same applies when an external logback configuration file is
provided through `-Dlogback.configurationFile=<file_path>`.

## Disabling ANSI colours in log output

Sometimes the log output needs to be free from ANSI colours. For example if the
log contents is being passed on to a log aggregator like Splunk or Logstash.
In these cases, the coloured output can be disabled by starting the application
with `-Dwsproxy.log.noformat=true` or setting the environment variable
`WSPROXY_LOG_ANSI_OFF=true`. Either way the log output will be written without
any ANSI codes.
