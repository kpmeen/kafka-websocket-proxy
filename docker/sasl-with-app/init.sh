#!/bin/bash

echo "----------------------------------------";
echo "      Kafka WebSocket Environment       ";
echo "----------------------------------------";

CURR_DIR=$(pwd)
SCRIPT_DIR=$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )

KAFKA_CONTAINER="kafka"
ZOOKEEPER_CONTAINER="zookeeper"

start_defined=false
stop_defined=false
status_defined=false
cleanup=false
initTopics=false
loop=false
debug=false

if [[ "$(uname)" == "Darwin" ]]; then
  export HOST_IP=$(ipconfig getifaddr en0)
elif [[ "$(uname)" == "Linux" ]]; then
  export HOST_IP=$(ip route get 1 | awk '{print $NF;exit}')
else
  echo "Script does not currently support Windows. The alternative option is:"
  echo "  1. Manually export and set the 'HOST_IP' environment variable."
  echo "  2. Make sure you are standing in the correct directory (same dir as docker-compose.yml file)"
  echo "  3. run: docker-compose up -d --build --force-recreate"
  echo "  4. stop: docker-compose down --remove-orphans --rmi"
  exit 1
fi

function createTopic() {
  name=$1
  policy=$2
  retention=$3

  echo "Creating topic with name $name with $policy policy and retention $retention milliseconds"

  baseCommand="docker exec -it $KAFKA_CONTAINER kafka-topics"
  args=" --zookeeper $ZOOKEEPER_CONTAINER:2181"
  args+=" --create"
  args+=" --if-not-exists"
  args+=" --partitions 3"
  args+=" --replication-factor 1"
  args+=" --topic $name"
  if [[ "$policy" = "delete" ]]; then
    args+=" --config cleanup.policy=$policy --config retention.ms=$retention"
  fi

  $baseCommand $args
}

function createTopics() {
  # Regular topics that retains data for 30 minutes.
  createTopic "test1" "delete" 1800000;
  createTopic "test2" "delete" 1800000;
  # Compacted topic
  createTopic "test3" "compact";
}

# Start containers defined in docker-compose file
function start() {
  echo "Ensuring necessary SSL files are ready..."
  cd sasl_ssl
  if [[ "$cleanup" = "true" ]]; then
    . ./create-certs.sh --clean
  else
    . ./create-certs.sh
  fi
  cd ..

  echo "Starting docker-compose build..."
  if [[ "$cleanup" = "true" ]]; then
    echo "recreating containers..."
    docker-compose up -d --build --force-recreate
  else
    echo "using existing containers..."
    docker-compose up -d --build --no-recreate
  fi

  # There's no need to initialise the topics unless they do not exist.
#  if [[ "$cleanup" = "true" ]]; then
    sleep 30;
    echo "Initializing topics..."
    createTopics
#  fi

  echo "Kafka cluster is ready!"
}

# Take down containers and remove any containers build by docker-compose
function stop() {
  echo "Stopping services..."
  if [[ "$cleanup" = "true" ]]; then
    compose_images=$(docker-compose images -q)
    docker-compose down --rmi local
    echo "$compose_images" | xargs docker rmi
    docker volume prune -f
  else
    docker-compose stop
  fi
  echo "Services stopped."
}

# Check the status of the containers
function status() {
  docker ps \
    -a \
    --filter=label=net.scalytica.kafka.wsproxy.container.type \
    --format "table {{.Names}}\t{{.Status}}"
}

function printUsage() {
  echo "Usage: backends.sh <command> [options]";
  echo "";
  echo "Commands:";
  echo "  start      Start the services defined in the docker-compose.yml file.";
  echo "  stop       Stops the services defined in the docker-compose.yml file.";
  echo "  restart    Will first stop the services before trying to start them again.";
  echo "  status     Prints the status for each of the services defined in the docker-compose.yml file.";
  echo "";
  echo "Arguments:";
  echo "  -c | --clean  If provided will cause the start/stop/restart commands to perform additional";
  echo "                cleanup operations of the docker containers, volumes and images.";
  echo "                Be aware that passing this argument will cause all accumulated data to disappear!";
  echo "                This option has no effect on the status command.";
  echo "  -l | --loop   When passed to the status command, will update the status output every 10 seconds.";
  echo "";
}

cd $SCRIPT_DIR

while [[ "$#" > 0 ]]; do
  case $1 in
    start)       start_defined=true;;
    stop)        stop_defined=true;;
    restart)     start_defined=true; stop_defined=true;;
    status)      status_defined=true;;
    init-topics) createTopics;;
    -c|--clean)  cleanup=true;;
    -l|--loop)   loop=true;;
    --debug)     debug=true;;
    *)           printUsage; exit 1;;
  esac;
  shift;
done

if [[ "$debug" = "true" ]]; then
  echo "start: $start_defined"
  echo "stop: $stop_defined"
  echo "status: $status_defined"
  echo "cleanup: $cleanup"
  echo "loop: $loop"
  echo "debug: $debug"
fi

if [[ "$status_defined" = "true" ]]; then
  if [[ "$loop" = "true" ]]; then
    while true; do status; sleep 10; echo -e "\r\033[7A\033[0K"; done
  else
    status
  fi
  exit 0

elif [[ "$start_defined" = "true" && "$stop_defined" = "true" ]]; then
  stop
  start
  exit 0

elif [[ "$start_defined" = "true" ]]; then
  start
  exit 0

elif [[ "$stop_defined" = "true" ]]; then
  stop
  exit 0

else
  printUsage
fi

cd $CURR_DIR