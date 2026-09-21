#!/usr/bin/env bash

set -Eeuo pipefail

TARGET_IMAGE="${1:?Usage: rollback-consumer.sh <target-image>}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
COMPOSE_ENV_FILE="${COMPOSE_ENV_FILE:-${PROJECT_ROOT}/infra/.env}"
DEPLOY_STATE_FILE="${CONSUMER_DEPLOY_STATE_FILE:-/var/lib/feelm-deploy/consumer-last-known-good}"

if [[ ! -f "${COMPOSE_ENV_FILE}" ]]; then
  echo "Consumer Compose environment file does not exist" >&2
  exit 1
fi

if [[ ! -d "$(dirname "${DEPLOY_STATE_FILE}")" || ! -w "$(dirname "${DEPLOY_STATE_FILE}")" ]]; then
  echo "Consumer deployment state directory is unavailable" >&2
  exit 1
fi

compose=(
  docker compose
  --env-file "${COMPOSE_ENV_FILE}"
  -f "${PROJECT_ROOT}/infra/compose/distributed.yml"
  --profile consumer
  --profile party-consumer
)

services=(consumer party-consumer)
containers=(feelm-foldin-consumer feelm-party-consumer)

env_value() {
  local name="$1"
  local fallback="$2"
  local value
  value="$({
    awk -F= -v key="${name}" '
      $0 !~ /^[[:space:]]*#/ && $1 == key {
        sub(/^[^=]*=/, "")
        print
        exit
      }
    ' "${COMPOSE_ENV_FILE}"
  } || true)"
  value="${value%\"}"
  value="${value#\"}"
  value="${value%\'}"
  value="${value#\'}"
  printf '%s' "${value:-${fallback}}"
}

KAFKA_TOPIC="$(env_value CONSUMER_KAFKA_TOPIC feelm.rating.v1)"
DISMISS_KAFKA_TOPIC="$(env_value CONSUMER_DISMISS_KAFKA_TOPIC feelm.dismiss.v1)"
REQUIRE_DISMISS_ASSIGNMENT="${CONSUMER_REQUIRE_DISMISS_ASSIGNMENT:-false}"
KAFKA_GROUP_ID="$(env_value CONSUMER_KAFKA_GROUP_ID feelm-foldin-consumer-v1)"
PARTY_KAFKA_TOPIC="$(env_value PARTY_CONSUMER_KAFKA_TOPIC feelm.party-refresh.v1)"
PARTY_KAFKA_GROUP_ID="$(env_value PARTY_CONSUMER_KAFKA_GROUP_ID feelm-party-consumer-v1)"

validate_image_ref() {
  local image="$1"
  [[ -n "${image}" && "${image}" != -* && "${image}" != *[[:space:]]* ]]
}

write_last_known_good() {
  local image="$1"
  local state_dir temporary

  state_dir="$(dirname "${DEPLOY_STATE_FILE}")"
  if [[ ! -d "${state_dir}" || ! -w "${state_dir}" ]]; then
    echo "Consumer deployment state directory is unavailable" >&2
    return 1
  fi
  temporary="${DEPLOY_STATE_FILE}.tmp.$$"
  umask 077
  printf '%s\n' "${image}" >"${temporary}"
  mv -f "${temporary}" "${DEPLOY_STATE_FILE}"
}

if ! validate_image_ref "${TARGET_IMAGE}"; then
  echo "Rollback image reference is invalid" >&2
  exit 1
fi

describe_group() {
  local group_id="$1"
  docker exec feelm-kafka \
    /opt/kafka/bin/kafka-consumer-groups.sh \
    --bootstrap-server 127.0.0.1:9092 \
    --describe \
    --group "${group_id}" 2>/dev/null || true
}

group_consumer_ids() {
  local topic="$1"
  awk -v topic="${topic}" '
    $2 == topic && $7 != "-" { ids[$7] = 1 }
    END { for (id in ids) print id }
  '
}

group_is_fully_assigned() {
  local topic="$1"
  awk -v topic="${topic}" '
    $2 == topic {
      rows += 1
      if ($7 == "-") unassigned += 1
      else ids[$7] = 1
    }
    END {
      id_count = 0
      for (id in ids) id_count += 1
      exit !(rows > 0 && unassigned == 0 && id_count == 1)
    }
  '
}

wait_for_rollback() {
  local replaced_foldin_ids="$1"
  local replaced_party_ids="$2"
  local attempts="${CONSUMER_DEPLOY_ATTEMPTS:-24}"
  local interval="${CONSUMER_DEPLOY_INTERVAL_SECONDS:-5}"
  local index container topic group_id replaced_ids state description current_ids all_ready

  for ((i = 1; i <= attempts; i++)); do
    all_ready=true
    for index in "${!containers[@]}"; do
      container="${containers[index]}"
      if ((index == 0)); then
        topic="${KAFKA_TOPIC}"
        group_id="${KAFKA_GROUP_ID}"
        replaced_ids="${replaced_foldin_ids}"
      else
        topic="${PARTY_KAFKA_TOPIC}"
        group_id="${PARTY_KAFKA_GROUP_ID}"
        replaced_ids="${replaced_party_ids}"
      fi
      state="$(docker inspect --format '{{.State.Status}} {{.RestartCount}}' \
        "${container}" 2>/dev/null || true)"
      if [[ "${state}" =~ ^(exited|dead)[[:space:]] ]] \
        || [[ "${state}" =~ ^running[[:space:]][1-9] ]]; then
        return 1
      fi
      if [[ "${state}" != "running 0" ]]; then
        all_ready=false
        continue
      fi
      description="$(describe_group "${group_id}")"
      if ! group_is_fully_assigned "${topic}" <<<"${description}"; then
        all_ready=false
        continue
      fi
      if [[ "${REQUIRE_DISMISS_ASSIGNMENT}" == true ]] \
        && ((index == 0)) \
        && ! group_is_fully_assigned "${DISMISS_KAFKA_TOPIC}" <<<"${description}"; then
        all_ready=false
        continue
      fi
      current_ids="$(group_consumer_ids "${topic}" <<<"${description}")"
      if [[ -n "${replaced_ids}" ]] \
        && grep -Fqx -f <(printf '%s\n' "${replaced_ids}") <<<"${current_ids}"; then
        all_ready=false
      fi
    done
    if [[ "${all_ready}" == true ]]; then
      return 0
    fi

    if ((i < attempts)); then
      sleep "${interval}"
    fi
  done

  return 1
}

replaced_foldin_ids="$(describe_group "${KAFKA_GROUP_ID}" | group_consumer_ids "${KAFKA_TOPIC}")"
replaced_party_ids="$(describe_group "${PARTY_KAFKA_GROUP_ID}" | group_consumer_ids "${PARTY_KAFKA_TOPIC}")"

echo "Rolling back Consumer to ${TARGET_IMAGE}"

if ! docker image inspect "${TARGET_IMAGE}" >/dev/null 2>&1; then
  docker pull "${TARGET_IMAGE}"
fi

CONSUMER_IMAGE="${TARGET_IMAGE}" "${compose[@]}" config --quiet
CONSUMER_IMAGE="${TARGET_IMAGE}" \
  "${compose[@]}" up -d --no-deps --force-recreate "${services[@]}"

if ! wait_for_rollback "${replaced_foldin_ids}" "${replaced_party_ids}"; then
  echo "Consumer rollback runtime verification failed" >&2
  echo "Consumer logs remain on the distributed host and are not copied to CI" >&2
  exit 1
fi

for container in "${containers[@]}"; do
  running_image="$(docker inspect --format '{{.Config.Image}}' "${container}")"
  if [[ "${running_image}" != "${TARGET_IMAGE}" ]]; then
    echo "Running Consumer image does not match rollback target: ${container}" >&2
    exit 1
  fi
done

write_last_known_good "${TARGET_IMAGE}"

echo "Consumer rollback completed: ${running_image}"
