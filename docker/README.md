# kafka-dev-sandbox


### The init.sh script

```sh
$ ./init.sh
----------------------------------------
        Secure Kafka Environment
----------------------------------------
Usage: init.sh <command> [arguments]

Commands:
  start        Start the services defined in the docker-compose setup.
  stop         Stops the services defined in the docker-compose setup.
  restart      Will first stop the services before trying to start them again.
  status       Prints the status for each of the services defined in the docker-compose setup.
  init-topics  Initialize topics.
  create-certs Create the necessary SSL keys and certificates.

Arguments:
  -a  | --all-services     Start or stop all services defined in the docker-compose files.
  -kc | --kafka-connect    Start or stop the Kafka Connect service.
  -c3 | --control-center   Start or stop the Confluent Control Center service, and enable Confluent metrics where applicable.
  -p  | --prometheus       Start or stop prometheus and grafana for capturing monitoring data.
  -s  | --skip-topics      Skip the initialization of topics.
  -c  | --clean            If provided will cause the start/stop/restart commands to perform additional
                           cleanup operations of the docker containers, volumes and images.
                           Be aware that passing this argument will cause all accumulated data to disappear!
                           This option has no effect on the status command.
  -i  | --images           When provided with --clean, will do a deep clean by removing the downloaded docker images.
  -o= | --only=            Only apply the command to the container specified after the equals sign.
  --loop                   When passed to the status command, will update the status output every 10 seconds.

```

#### Examples

**start**:
```sh
./init.sh start
```

**stop**:
```sh
./init.sh stop
```

**start with no data**:
```sh
./init.sh start --clean
```

**stop and remove containers**:
```sh
./init.sh stop --clean
```

**stop and remove containers and images**:
```sh
./init.sh stop --clean --images
```

**start all services**
```sh
./init.sh start --all-services
```

**stop all services**
```sh
./init.sh stop --all-services
```

**start kafka, zookeeper, schema-registry and kafka-connect**
```sh
./init.sh start --kafka-connect
```

**stop kafka, zookeeper, schema-registry and kafka-connect**
```sh
./init.sh stop --kafka-connect
```

**start prometheus only**
```sh
./init.sh start --prometheus --only=prometheus
```

**stop kafka, zookeeper, schema-registry and kafka-connect**
```sh
./init.sh stop --kafka-connect
```

**restart**:
```sh
./init.sh restart
```

**restart and remove containers**: _recommended_
```sh
./init.sh restart --clean
```

**restart and remove containers and images**:
```sh
./init.sh restart --clean --images
```

**restart and clean schema-registry only**:
```sh
./init.sh restart --only=schema-registry
```

**restart all containers but do not recreate topics**
```sh
./init.sh restart --all-services --skip-topics
```

**check status**:
```sh
./init.sh status
```

**continually check status**:
```sh
./init.sh status --loop
```


## sasl-plain

### Creating certificates for the sasl-plain setup

The `init.sh` script for the sasl-plain setup will automatically create the necessary
certs and keystores. But, it can be triggered manually with the following command which
will recreate the files under the `sasl-plain/ssl` directory:

```sh
$ ./init.sh create-certs
```


