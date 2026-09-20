#!/usr/bin/env bash

set -Eeuo pipefail

KAFKA_CONTAINER="${KAFKA_CONTAINER:-feelm-kafka}"
KAFKA_BOOTSTRAP_SERVER="${KAFKA_BOOTSTRAP_SERVER:-127.0.0.1:9092}"
KAFKA_TOPIC_PARTITIONS="${KAFKA_TOPIC_PARTITIONS:-3}"
KAFKA_TOPIC_READY_ATTEMPTS="${KAFKA_TOPIC_READY_ATTEMPTS:-12}"
KAFKA_TOPIC_READY_INTERVAL_SECONDS="${KAFKA_TOPIC_READY_INTERVAL_SECONDS:-5}"
topics=(
  feelm.rating.v1
  feelm.dismiss.v1
  feelm.view.v1
  feelm.recommendation-click.v1
  feelm.ott-click.v1
  feelm.candidate-updated.v1
  feelm.party-refresh.v1
  feelm.party-candidate-updated.v1
)

if [[ ! "${KAFKA_TOPIC_PARTITIONS}" =~ ^[1-9][0-9]*$ ]]; then
  echo "KAFKA_TOPIC_PARTITIONS must be a positive integer" >&2
  exit 2
fi

kafka_topics() {
  docker exec "${KAFKA_CONTAINER}" \
    /opt/kafka/bin/kafka-topics.sh \
    --bootstrap-server "${KAFKA_BOOTSTRAP_SERVER}" "$@"
}

ready=false
for ((attempt = 1; attempt <= KAFKA_TOPIC_READY_ATTEMPTS; attempt++)); do
  if kafka_topics --list >/dev/null 2>&1; then
    ready=true
    break
  fi
  if ((attempt < KAFKA_TOPIC_READY_ATTEMPTS)); then
    sleep "${KAFKA_TOPIC_READY_INTERVAL_SECONDS}"
  fi
done
if [[ "${ready}" != true ]]; then
  echo "Kafka broker did not become ready" >&2
  exit 1
fi

for topic in "${topics[@]}"; do
  kafka_topics \
    --create \
    --if-not-exists \
    --topic "${topic}" \
    --partitions "${KAFKA_TOPIC_PARTITIONS}" \
    --replication-factor 1 >/dev/null

  description="$(kafka_topics --describe --topic "${topic}")"
  if ! grep -Eq "(^|[[:space:]])PartitionCount:[[:space:]]*${KAFKA_TOPIC_PARTITIONS}([[:space:]]|$)" \
    <<<"${description}"; then
    echo "Kafka topic has an unexpected partition count: ${topic}" >&2
    exit 1
  fi
  echo "Kafka topic ensured: ${topic} partitions=${KAFKA_TOPIC_PARTITIONS}"
done
