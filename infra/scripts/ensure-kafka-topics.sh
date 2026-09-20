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

# The current single-broker contract requires one replica per partition.
readonly replication_factor=1
for variable in KAFKA_TOPIC_PARTITIONS KAFKA_TOPIC_READY_ATTEMPTS; do
  if [[ ! "${!variable}" =~ ^[1-9][0-9]*$ ]]; then
    echo "${variable} must be a positive integer" >&2
    exit 2
  fi
done
if [[ ! "${KAFKA_TOPIC_READY_INTERVAL_SECONDS}" =~ ^(0|[1-9][0-9]*)$ ]]; then
  echo "KAFKA_TOPIC_READY_INTERVAL_SECONDS must be a non-negative integer" >&2
  exit 2
fi

kafka_topics() {
  docker exec "${KAFKA_CONTAINER}" \
    /opt/kafka/bin/kafka-topics.sh \
    --bootstrap-server "${KAFKA_BOOTSTRAP_SERVER}" "$@"
}

validate_topic() {
  local topic="$1" description pattern
  # --topic is a regex for describe. Escape dots and anchor the exact name.
  pattern="^${topic//./\\.}$"
  description="$(kafka_topics --describe --topic "${pattern}")" || return 1
  if ! awk -v topic="${topic}" -v partitions="${KAFKA_TOPIC_PARTITIONS}" \
    -v replicas="${replication_factor}" '
    function field(label, i) {
      for (i = 1; i < NF; i++) if ($i == label ":") return $(i + 1)
      return ""
    }
    /Topic:/ {
      if (field("Topic") != topic) { bad = 1; next }
      if (/PartitionCount:/) {
        summaries++
        if (field("PartitionCount") != partitions || field("ReplicationFactor") != replicas)
          bad = 1
      } else if (/Partition:/) {
        id = field("Partition")
        if (id !~ /^[0-9]+$/ || id + 0 >= partitions || seen[id]++) bad = 1
        rows++
        assignment = field("Replicas")
        if (assignment !~ /^[0-9]+(,[0-9]+)*$/ || split(assignment, ids, ",") != replicas)
          bad = 1
      } else bad = 1
    }
    END { exit (bad || summaries != 1 || rows != partitions) ? 1 : 0 }
  ' <<<"${description}"; then
    echo "Kafka topic configuration mismatch or incomplete metadata: ${topic}; expected partitions=${KAFKA_TOPIC_PARTITIONS} replication-factor=${replication_factor}" >&2
    return 1
  fi
  echo "Kafka topic verified: ${topic} partitions=${KAFKA_TOPIC_PARTITIONS} replication-factor=${replication_factor}"
}

ready=false
for ((attempt = 1; attempt <= KAFKA_TOPIC_READY_ATTEMPTS; attempt++)); do
  if existing_topics="$(kafka_topics --list)"; then
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

# Validate all existing topics before creating anything. A read/auth error is
# never treated as proof that a topic is missing.
missing_topics=()
for topic in "${topics[@]}"; do
  if grep -Fxq -- "${topic}" <<<"${existing_topics}"; then
    validate_topic "${topic}"
  else
    missing_topics+=("${topic}")
  fi
done

for topic in "${missing_topics[@]}"; do
  kafka_topics \
    --create \
    --if-not-exists \
    --topic "${topic}" \
    --partitions "${KAFKA_TOPIC_PARTITIONS}" \
    --replication-factor "${replication_factor}" >/dev/null
done

# Recheck after creation, including a concurrent creator winning the race.
for topic in "${topics[@]}"; do
  validate_topic "${topic}"
done
