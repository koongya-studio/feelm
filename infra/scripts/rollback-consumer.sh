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
)

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
KAFKA_GROUP_ID="$(env_value CONSUMER_KAFKA_GROUP_ID feelm-foldin-consumer-v1)"

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
  docker exec feelm-kafka \
    /opt/kafka/bin/kafka-consumer-groups.sh \
    --bootstrap-server 127.0.0.1:9092 \
    --describe \
    --group "${KAFKA_GROUP_ID}" 2>/dev/null || true
}

group_consumer_ids() {
  awk -v topic="${KAFKA_TOPIC}" '
    $2 == topic && $7 != "-" { ids[$7] = 1 }
    END { for (id in ids) print id }
  '
}

group_is_fully_assigned() {
  awk -v topic="${KAFKA_TOPIC}" '
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
  local replaced_ids="$1"
  local attempts="${CONSUMER_DEPLOY_ATTEMPTS:-24}"
  local interval="${CONSUMER_DEPLOY_INTERVAL_SECONDS:-5}"
  local state description current_ids

  for ((i = 1; i <= attempts; i++)); do
    state="$(
      docker inspect --format '{{.State.Status}} {{.RestartCount}}' \
        feelm-foldin-consumer 2>/dev/null || true
    )"
    if [[ "${state}" =~ ^(exited|dead)[[:space:]] ]] || [[ "${state}" =~ ^running[[:space:]][1-9] ]]; then
      return 1
    fi

    if [[ "${state}" == "running 0" ]]; then
      description="$(describe_group)"
      if group_is_fully_assigned <<<"${description}"; then
        current_ids="$(group_consumer_ids <<<"${description}")"
        if [[ -z "${replaced_ids}" ]] || ! grep -Fqx -f <(printf '%s\n' "${replaced_ids}") <<<"${current_ids}"; then
          return 0
        fi
      fi
    fi

    if ((i < attempts)); then
      sleep "${interval}"
    fi
  done

  return 1
}

replaced_ids="$(describe_group | group_consumer_ids)"

echo "Rolling back Consumer to ${TARGET_IMAGE}"

if ! docker image inspect "${TARGET_IMAGE}" >/dev/null 2>&1; then
  docker pull "${TARGET_IMAGE}"
fi

CONSUMER_IMAGE="${TARGET_IMAGE}" "${compose[@]}" config --quiet
CONSUMER_IMAGE="${TARGET_IMAGE}" \
  "${compose[@]}" up -d --no-deps --force-recreate consumer

if ! wait_for_rollback "${replaced_ids}"; then
  echo "Consumer rollback runtime verification failed" >&2
  echo "Consumer logs remain on the distributed host and are not copied to CI" >&2
  exit 1
fi

running_image="$(docker inspect --format '{{.Config.Image}}' feelm-foldin-consumer)"
if [[ "${running_image}" != "${TARGET_IMAGE}" ]]; then
  echo "Running Consumer image does not match rollback target" >&2
  exit 1
fi

write_last_known_good "${TARGET_IMAGE}"

echo "Consumer rollback completed: ${running_image}"
