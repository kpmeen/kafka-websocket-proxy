#!/bin/bash

######################################################################################
# Init script to help spin up a local Kafka environment. With the option to include
# extra services from the Kafka eco-system.
#
# author: Knut Petter Meen
######################################################################################

CURR_DIR=$(pwd);
SCRIPT_DIR=$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd );
KAFKA_CONTAINER_1="kafka1";

export CONFLUENT_VERSION="6.1.1"

docker_exec_cmd="docker exec";

# including the ssl/create-certs.sh script to generate certs
source $SCRIPT_DIR/ssl/create-certs.sh

# Commands defined
start_defined=false;
stop_defined=false;
status_defined=false;
init_topics_defined=false;
create_certs_defined=false;

# Command argument flags
all_services=false;
enable_kafka_connect=false;
enable_prometheus=false;
enable_control_center=false;
create_topics=true;
cleanup=false;
deep_clean=false;
loop=false;
debug=false;
only_container=;

if [[ "$(uname)" == "Darwin" ]]; then
  HOST_IP=$(ipconfig getifaddr en0);
  export HOST_IP;
elif [[ "$(uname)" == "Linux" ]]; then
  HOST_IP=$(ip route get 1 | awk '{print $NF;exit}');
  export HOST_IP;
else
  echo "Script does not currently support Windows. The alternative option is:";
  echo "  1. Manually export and set the 'HOST_IP' environment variable.";
  echo "  2. Make sure you are standing in the correct directory (same dir as docker-compose.yml file)";
  echo "  3. run: docker-compose up -d --build --force-recreate";
  echo "  4. stop: docker-compose down --remove-orphans --rmi";
  exit 1;
fi

function createTopic() {
  name=$1;
  policy=$2;
  retention=$3;
  echo "Creating topic with name $name with $policy policy and retention $retention milliseconds";
  local createCmd=""
  createCmd+="$docker_exec_cmd -it $KAFKA_CONTAINER_1 kafka-topics";
  createCmd+=" --bootstrap-server $KAFKA_CONTAINER_1:9092";
  createCmd+=" --create";
  createCmd+=" --if-not-exists";
  createCmd+=" --partitions 3";
  createCmd+=" --replication-factor 1";
  createCmd+=" --topic $name";
  if [[ "$policy" = "delete" ]]; then
    createCmd+=" --config cleanup.policy=$policy --config retention.ms=$retention";
  fi
  $createCmd;
}

function createTopics() {
  # If cleanup the topics are removed before being recreated.
  if [[ "$cleanup" = "true" ]]; then
    sleep 30;
    echo "Deleting topics...";
    deleteTopics;
  fi
  echo "Initializing topics...";
  # Regular topics that retains data for 30 minutes.
  createTopic "test1" "delete" 1800000;
  createTopic "test2" "delete" 1800000;
  # Compacted topic
  createTopic "baz" "compact";
}

function deleteTopic() {
  local topicName=$1;
  echo "Trying to delete $topicName...";
  local checkCmd="";
  checkCmd+="$docker_exec_cmd -it $KAFKA_CONTAINER_1 kafka-topics";
  checkCmd+=" --bootstrap-server $KAFKA_CONTAINER_1:9092";
  checkCmd+=" --topic $topicName";
  checkCmd+=" --delete";
  # Delete topic and ignore output. If it fails the topic doesn't exist anyway.
  $checkCmd >/dev/null 2>&1;
}

function deleteTopics() {
  deleteTopic "test1";
  deleteTopic "test2";
  deleteTopic "baz";
}

function buildComposeCmd() {
  local  __cmd=$1;
  local cmd_builder="docker-compose -f docker-compose.yml";
  if [[ "$all_services" = "true" ]]; then
    cmd_builder+=" -f docker-compose-connect.yml";
    cmd_builder+=" -f docker-compose-c3.yml";
    cmd_builder+=" -f docker-compose-prometheus.yml";
  else
    if [[ "$enable_kafka_connect" = "true" ]]; then
      cmd_builder+=" -f docker-compose-connect.yml";
    fi
    if [[ "$enable_prometheus" = "true" ]]; then
      cmd_builder+=" -f docker-compose-prometheus.yml";
    fi
    if [[ "$enable_control_center" = "true" ]]; then
      cmd_builder+=" -f docker-compose-c3.yml";
    fi
  fi
  if [[ "$debug" = "true" ]]; then
    echo "Using docker-compose with files: $cmd_builder";
  fi

  eval $__cmd="'$cmd_builder'";
}

# Start containers defined in docker-compose file
function start() {
  echo "Starting docker-compose build...";
  local the_container=$1;
  local the_cmd="";
  buildComposeCmd the_cmd;
  if [[ "$cleanup" = "true" ]]; then
    echo "Removing cert files...";
    recreateCerts
    echo "recreating containers...";
    $the_cmd up -d --build --force-recreate $the_container;
  else
    echo "using existing containers...";
    $the_cmd up -d --build --no-recreate $the_container;
  fi

  if [[ "$create_topics" = "true" ]]; then
    createTopics;
  fi

  echo "Kafka cluster is ready!";
}

# Take down containers and remove any containers built by docker-compose
function stop() {
  echo "Stopping services...";
  local the_container=$1;
  local the_cmd="";
  buildComposeCmd the_cmd;
  if [[ "$cleanup" = "true" ]]; then
    if [[ -n $the_container ]]; then
      the_cmd+=" rm -s -f $the_container";
    else
      the_cmd+=" down --rmi local";
    fi
    $the_cmd;
    
    if [[ "$deep_clean" = "true" ]]; then
      echo "Cleaning up images...";
      local compose_images=$(docker-compose images -q);
      echo "$compose_images" | xargs docker rmi;
    fi
    # Prune any unused docker volumes to free up disk space
    docker volume prune -f;
    deleteCerts
  else
    if [[ -n $the_container ]]; then
      the_cmd+=" stop $the_container";
    else
      the_cmd+=" down";
    fi
    echo "$the_cmd";
    $the_cmd;
  fi
  echo "Services stopped.";
}

# Check the status of the containers
function status() {
  docker ps \
    -a \
    --filter=label=kafka.sandbox.container.type \
    --format "table {{.Names}}\t{{.Status}}";
}

function listActiveServices() {
  docker ps \
    --filter=label=kafka.sandbox.container.type \
    --format "table {{.Names}}";
}

function printHeader() {
  echo "----------------------------------------";
  echo "        Secure Kafka Environment        ";
  echo "----------------------------------------";
}

function printUsage() {
  echo "Usage: init.sh <command> [arguments]";
  echo "";
  echo "Commands:";
  echo "  start        Start the services defined in the docker-compose setup.";
  echo "  stop         Stops the services defined in the docker-compose setup.";
  echo "  restart      Will first stop the services before trying to start them again.";
  echo "  status       Prints the status for each of the services defined in the docker-compose setup.";
  echo "  init-topics  Initialize topics.";
  echo "  create-certs Create the necessary SSL keys and certificates.";
  echo "  list         List active containers / services.";
  echo "";
  echo "Arguments:";
  echo "  -a  | --all-services     Start or stop all services defined in the docker-compose files.";
  echo "  -kc | --kafka-connect    Start or stop the Kafka Connect service.";
  echo "  -c3 | --control-center   Start or stop the Confluent Control Center service, and enable Confluent metrics where applicable.";
  echo "  -p  | --prometheus       Start or stop prometheus and grafana for capturing monitoring data.";
  echo "  -s  | --skip-topics      Skip the initialization of topics.";
  echo "  -c  | --clean            If provided will cause the start/stop/restart commands to perform additional";
  echo "                           cleanup operations of the docker containers, volumes and images.";
  echo "                           Be aware that passing this argument will cause all accumulated data to disappear!";
  echo "                           This option has no effect on the status command.";
  echo "  -i  | --images           When provided with --clean, will do a deep clean by removing the downloaded docker images.";
  echo "  -o= | --only=            Only apply the command to the container specified after the equals sign.";
  echo "  --loop                   When passed to the status command, will update the status output every 10 seconds.";
  echo "";
}

cd "$SCRIPT_DIR" || exit;

echo "arguments are: $@";

while [[ "$#" -gt 0 ]]; do
  case $1 in
    start)                       start_defined=true;;
    stop)                        stop_defined=true;;
    restart)                     start_defined=true; stop_defined=true;;
    status)                      status_defined=true;;
    init-topics)                 init_topics_defined=true;;
    create-certs)                create_certs_defined=true;;
    list)                        listActiveServices; exit 0;;
    -a|--all-services)           all_services=true;;
    -kc|--kafka-connect)         enable_kafka_connect=true;;
    -c3|--control-center)        enable_control_center=true;;
    -p|--prometheus)             enable_prometheus=true;;
    -s|--skip-topics)            create_topics=false;;
    -c|--clean)                  cleanup=true;;
    -i|--images)                 deep_clean=true;;
    -o=*|--only=*)               only_container="${1#*=}"; create_topics=false;;
    --loop)                      loop=true;;
    --debug)                     debug=true;;
    *)                           printUsage; exit 1;;
  esac;
  shift;
done

if [[ "$debug" = "true" ]]; then
  echo "start: $start_defined";
  echo "stop: $stop_defined";
  echo "status: $status_defined";
  echo "init_topics_defined: $init_topics_defined";
  echo "all_services: $all_services";
  echo "enable_kafka_connect: $enable_kafka_connect";
  echo "enable_control_center: $enable_control_center";
  echo "enable_prometheus: $enable_prometheus";
  echo "create_topics: $create_topics";
  echo "cleanup: $cleanup";
  echo "deep_clean: $deep_clean";
  echo "only_container: $only_container";
  echo "loop: $loop";
  echo "debug: $debug";
fi

if [[ "$status_defined" = "true" ]]; then
  if [[ "$loop" = "true" ]]; then
    while true; do
      printf "\ec";
      printHeader;
      status;
      sleep 10;
    done
  else
    printHeader;
    status;
  fi
  exit 0;

elif [[ "$create_certs_defined" = "true" ]]; then
  printHeader;
  recreateCerts
  exit 0;

elif [[ "$start_defined" = "true" && "$stop_defined" = "true" ]]; then
  printHeader;
  stop $only_container;
  start $only_container;
  exit 0;

elif [[ "$start_defined" = "true" ]]; then
  printHeader;
  start $only_container;
  exit 0;

elif [[ "$stop_defined" = "true" ]]; then
  printHeader;
  stop $only_container;
  exit 0;

elif [[ "$init_topics_defined" = "true" ]]; then
  printHeader;
  createTopics;
  exit 0;

else
  printHeader;
  printUsage;
fi

cd "$CURR_DIR" || exit;