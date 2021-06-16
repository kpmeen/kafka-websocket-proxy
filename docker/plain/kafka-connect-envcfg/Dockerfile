ARG IMAGE_VERSION=latest
FROM confluentinc/cp-kafka-connect:${IMAGE_VERSION}

RUN \
  echo "PRE-INSTALLING CONNECTORS..." && \
  # echo "===> Installing Debezium Postgres Connector ..." && \
  # confluent-hub install debezium/debezium-connector-postgresql:latest --no-prompt && \
  echo "===> Installing Kafka Connect Datagen Connector ..." && \
  confluent-hub install confluentinc/kafka-connect-datagen:latest --no-prompt && \
  echo "===> Installing Kafka Connect RSS Connector" && \
  confluent-hub install kaliy/kafka-connect-rss:0.1.0 --no-prompt