#!/usr/bin/env bash

set -Eeuo pipefail

ACTION="${1:?Usage: deploy-consumer.sh <candidate-image>|--initialize-state}"
INITIALIZE_ONLY=false
if [[ "${ACTION}" == "--initialize-state" ]]; then
  INITIALIZE_ONLY=true
  CANDIDATE_IMAGE=""
else
  CANDIDATE_IMAGE="${ACTION}"
fi

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

  validate_image_ref "${image}" || return 1
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

verify_current_commit() {
  if [[ -n "${CI_COMMIT_SHA:-}" ]]; then
    /usr/bin/python3 -I -B "${SCRIPT_DIR}/ci-deploy-current-commit.py" \
      --pipeline-source "${DEPLOY_PIPELINE_SOURCE:-push}"
  fi
}

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

wait_for_candidate() {
  local previous_foldin_ids="$1"
  local previous_party_ids="$2"
  local attempts="${CONSUMER_DEPLOY_ATTEMPTS:-24}"
  local interval="${CONSUMER_DEPLOY_INTERVAL_SECONDS:-5}"
  local index container topic group_id previous_ids state description current_ids all_ready

  for ((i = 1; i <= attempts; i++)); do
    all_ready=true
    for index in "${!containers[@]}"; do
      container="${containers[index]}"
      if ((index == 0)); then
        topic="${KAFKA_TOPIC}"
        group_id="${KAFKA_GROUP_ID}"
        previous_ids="${previous_foldin_ids}"
      else
        topic="${PARTY_KAFKA_TOPIC}"
        group_id="${PARTY_KAFKA_GROUP_ID}"
        previous_ids="${previous_party_ids}"
      fi
      state="$(
        docker inspect --format '{{.State.Status}} {{.RestartCount}}' \
          "${container}" 2>/dev/null || true
      )"
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
      current_ids="$(group_consumer_ids "${topic}" <<<"${description}")"
      if [[ -n "${previous_ids}" ]] \
        && grep -Fqx -f <(printf '%s\n' "${previous_ids}") <<<"${current_ids}"; then
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

foldin_running_before="$(
  docker inspect --format '{{.Config.Image}}' feelm-foldin-consumer 2>/dev/null || true
)"
party_running_before="$(
  docker inspect --format '{{.Config.Image}}' feelm-party-consumer 2>/dev/null || true
)"
previous_foldin_ids="$(describe_group "${KAFKA_GROUP_ID}" | group_consumer_ids "${KAFKA_TOPIC}")"
previous_party_ids="$(describe_group "${PARTY_KAFKA_GROUP_ID}" | group_consumer_ids "${PARTY_KAFKA_TOPIC}")"
previous_image=""

if [[ -f "${DEPLOY_STATE_FILE}" ]]; then
  IFS= read -r previous_image <"${DEPLOY_STATE_FILE}" || true
  if ! validate_image_ref "${previous_image}"; then
    echo "Consumer deployment state is invalid" >&2
    exit 1
  fi
elif [[ "${INITIALIZE_ONLY}" == true \
  && -n "${foldin_running_before}" \
  && "${foldin_running_before}" == "${party_running_before}" ]]; then
  if wait_for_candidate "" ""; then
    previous_image="${foldin_running_before}"
    if ! write_last_known_good "${previous_image}"; then
      echo "Could not initialize the last-known-good Consumer image" >&2
      exit 1
    fi
  fi
fi

if [[ "${INITIALIZE_ONLY}" == true ]]; then
  if [[ -z "${previous_image}" ]]; then
    echo "A healthy, fully assigned running Consumer is required to initialize deployment state" >&2
    exit 1
  fi
  if [[ "${foldin_running_before}" != "${previous_image}" \
    || "${party_running_before}" != "${previous_image}" ]]; then
    echo "Running Consumer images do not match the initialized deployment state" >&2
    exit 1
  fi
  if ! wait_for_candidate "" ""; then
    echo "Two healthy, fully assigned running Consumers are required to initialize deployment state" >&2
    exit 1
  fi
  echo "Consumer deployment state initialized: ${previous_image}"
  exit 0
fi

if [[ -z "${previous_image}" ]]; then
  echo "Consumer deployment state is not initialized; run deploy-consumer.sh --initialize-state first" >&2
  exit 1
fi

if [[ "${foldin_running_before}" != "${previous_image}" \
  || "${party_running_before}" != "${previous_image}" ]]; then
  echo "Running Consumers differ from the last-known-good image; reconciling before deployment" >&2
  if ! COMPOSE_ENV_FILE="${COMPOSE_ENV_FILE}" \
    CONSUMER_DEPLOY_STATE_FILE="${DEPLOY_STATE_FILE}" \
    bash "${SCRIPT_DIR}/rollback-consumer.sh" "${previous_image}"; then
    echo "Consumer reconciliation failed; manual recovery is required" >&2
    exit 1
  fi
  foldin_running_before="${previous_image}"
  party_running_before="${previous_image}"
  previous_foldin_ids="$(describe_group "${KAFKA_GROUP_ID}" | group_consumer_ids "${KAFKA_TOPIC}")"
  previous_party_ids="$(describe_group "${PARTY_KAFKA_GROUP_ID}" | group_consumer_ids "${PARTY_KAFKA_TOPIC}")"
fi

echo "Previous Consumer image: ${previous_image:-none}"
echo "Candidate Consumer image: ${CANDIDATE_IMAGE}"

if ! validate_image_ref "${CANDIDATE_IMAGE}"; then
  echo "Candidate image reference is invalid" >&2
  exit 1
fi

if ! docker pull "${CANDIDATE_IMAGE}"; then
  echo "Candidate image pull failed; running Consumer was not changed" >&2
  exit 1
fi

if ! CONSUMER_IMAGE="${CANDIDATE_IMAGE}" "${compose[@]}" config --quiet; then
  echo "Candidate Compose configuration is invalid; running Consumer was not changed" >&2
  exit 1
fi

restore_previous_image() {
  local reason="$1"

  echo "${reason}" >&2
  echo "Consumer logs remain on the distributed host and are not copied to CI" >&2

  if [[ -z "${previous_image}" ]]; then
    echo "No previous Consumer image is available for rollback" >&2
    docker stop "${containers[@]}" >/dev/null 2>&1 || true
    return 1
  fi

  echo "Restoring previous Consumer image: ${previous_image}" >&2
  if ! COMPOSE_ENV_FILE="${COMPOSE_ENV_FILE}" \
    CONSUMER_DEPLOY_STATE_FILE="${DEPLOY_STATE_FILE}" \
    bash "${SCRIPT_DIR}/rollback-consumer.sh" "${previous_image}"; then
    echo "Consumer rollback failed; manual recovery is required" >&2
    return 1
  fi

  echo "Candidate deployment failed; previous Consumer image was restored" >&2
  return 1
}

if ! verify_current_commit; then
  echo "Candidate deployment is no longer the current main commit" >&2
  exit 1
fi

if ! CONSUMER_IMAGE="${CANDIDATE_IMAGE}" \
  "${compose[@]}" up -d --no-deps --force-recreate "${services[@]}"; then
  restore_previous_image "Candidate Consumer deployment command failed"
fi

if ! wait_for_candidate "${previous_foldin_ids}" "${previous_party_ids}"; then
  restore_previous_image "Candidate Consumer runtime verification failed"
fi

for container in "${containers[@]}"; do
  running_image="$(docker inspect --format '{{.Config.Image}}' "${container}")"
  if [[ "${running_image}" != "${CANDIDATE_IMAGE}" ]]; then
    restore_previous_image "Running Consumer image does not match candidate image: ${container}"
  fi
done

if ! verify_current_commit; then
  restore_previous_image "Candidate deployment became stale during runtime verification"
fi

if ! write_last_known_good "${CANDIDATE_IMAGE}"; then
  restore_previous_image "Could not persist the verified Consumer image"
fi

echo "Consumer deployment completed: ${running_image}"
