#!/bin/bash

schema_subject="test1-value"
header="Content-Type: application/json"
connector_config="@datagen-test.json"
escaped_avro_schema=$(cat sample-schema.avsc | sed 's/"/\\"/g')
schema_request_body=$(cat << EOF
{
  "schema": "${escaped_avro_schema//[$'\t\r\n ']}",
  "schemaType": "AVRO"
}
EOF
)

function schemaExists() {
  local res=$(curl -X GET -s -H "${header}" http://localhost:8081/subjects/$schema_subject/versions | grep -o "\"message\":\"Subject '$schema_subject' not found.\"")
  if [[ -z $res ]]; then
    echo "true"
  else
    echo "false"
  fi
}

function publishSchemaToSchemaRegistry() {
  local is_registered=$(schemaExists)
  if [[ "$is_registered" = "false" ]]; then
    echo "Publishing Avro schema..."
    local res=$(curl -X POST -s -o /dev/null -w "%{http_code}" -H "${header}" --data "${schema_request_body}" http://localhost:8081/subjects/$schema_subject/versions)
    if [[ $res -ne 200 ]]; then
      echo "Failed to publish schema. Got HTTP status $res."
      exit 1;
    fi
    echo "Successfully published Avro schema."
  fi
}

function deployDatagenConnector() {
  echo "Posting connector config..."
  local res=$(curl -X POST -s -o /dev/null -w "%{http_code}" -H "${header}" --data ${connector_config} http://localhost:8083/connectors)
  if [[ $res -ne 200 ]]; then
    echo "Failed to publish kafka-connect-datagen connector. Got HTTP status $res."
    exit 1;
  fi
  echo "Successfully published kafka-connect-datagen connector."

}

function removeConnector() {
  echo "Removing connector..."
  local res=$(curl -X DELETE -s -o /dev/null -w "%{http_code}" -H "${header}" http://localhost:8083/connectors)
  if [[ $res -ne 200 ]]; then
    echo "Failed to remove kafka-connect-datagen connector. Got HTTP status $res."
    exit 1;
  fi
  echo "Successfully removed kafka-connect-datagen connector."
}

function printUsage() {
   echo "Usage: datagen.sh <command> [arguments]"
   echo ""
   echo "Commands:"
   echo "  deploy           Deploy the kafka-connect-datagen connector."
   echo "  remove           Remove the kafka-connect-datagen-connector."
   echo "  check-schema     Quick check to see if the schema is already registered."
   echo "  publish-schema   Publish the schema to the schema registry without deploying the kafka-connect-datagen connector."
   echo ""
   echo "Arguments:"
   echo "  -s | --with-schema    When defined with deploy, will first publish the Avro schema to the schema-registry."
   echo ""
   echo "Examples:"
   echo "- Deploy using the sample avro schema: ./datagen.sh deploy --with-schema"
   echo "- Remove the connector: ./datagen.sh remove"
   echo "- Publish the Avro schema: ./datagen.sh publish-schema"
 }

deploy_defined=false
remove_defined=false
publish_schema=false

while [[ "$#" -gt 0 ]]; do
   case $1 in
     deploy)                      deploy_defined=true;;
     remove)                      remove_defined=true;;
     publish-schema)              publish_schema=true;;
     check-schema)                [[ "$(schemaExists)" = "true" ]] && echo "Schema for subject $schema_subject is already registered." || echo "Schema for subject $schema_subject is not registered"; exit 0;;
     -s|--with-schema)            publish_schema=true;;
     *)                           printUsage; exit 1;;
   esac;
   shift;
 done

if [[ "$publish_schema" = "true" ]]; then
  publishSchemaToSchemaRegistry
fi

if [[ "$deploy_defined" = "true" ]]; then
  deployDatagenConnector
elif [[ "$remove_defined" = "true" ]]; then
  removeConnector
else
  echo "Skipping deployment of connector"
fi

exit 0