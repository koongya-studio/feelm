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

wait_for_candidate() {
  local previous_ids="$1"
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
        if [[ -z "${previous_ids}" ]] || ! grep -Fqx -f <(printf '%s\n' "${previous_ids}") <<<"${current_ids}"; then
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

running_before="$(
  docker inspect --format '{{.Config.Image}}' feelm-foldin-consumer 2>/dev/null || true
)"
previous_ids="$(describe_group | group_consumer_ids)"
previous_image=""

if [[ -f "${DEPLOY_STATE_FILE}" ]]; then
  IFS= read -r previous_image <"${DEPLOY_STATE_FILE}" || true
  if ! validate_image_ref "${previous_image}"; then
    echo "Consumer deployment state is invalid" >&2
    exit 1
  fi
elif [[ "${INITIALIZE_ONLY}" == true && -n "${running_before}" ]]; then
  running_state="$(
    docker inspect --format '{{.State.Status}} {{.RestartCount}}' \
      feelm-foldin-consumer 2>/dev/null || true
  )"
  running_group="$(describe_group)"
  if [[ "${running_state}" == "running 0" ]] && group_is_fully_assigned <<<"${running_group}"; then
    previous_image="${running_before}"
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
  if [[ "${running_before}" != "${previous_image}" ]]; then
    echo "Running Consumer image does not match the initialized deployment state" >&2
    exit 1
  fi
  running_state="$(
    docker inspect --format '{{.State.Status}} {{.RestartCount}}' \
      feelm-foldin-consumer 2>/dev/null || true
  )"
  running_group="$(describe_group)"
  if [[ "${running_state}" != "running 0" ]] || ! group_is_fully_assigned <<<"${running_group}"; then
    echo "A healthy, fully assigned running Consumer is required to initialize deployment state" >&2
    exit 1
  fi
  echo "Consumer deployment state initialized: ${previous_image}"
  exit 0
fi

if [[ -z "${previous_image}" ]]; then
  echo "Consumer deployment state is not initialized; run deploy-consumer.sh --initialize-state first" >&2
  exit 1
fi

if [[ "${running_before}" != "${previous_image}" ]]; then
  echo "Running Consumer differs from the last-known-good image; reconciling before deployment" >&2
  if ! COMPOSE_ENV_FILE="${COMPOSE_ENV_FILE}" \
    CONSUMER_DEPLOY_STATE_FILE="${DEPLOY_STATE_FILE}" \
    bash "${SCRIPT_DIR}/rollback-consumer.sh" "${previous_image}"; then
    echo "Consumer reconciliation failed; manual recovery is required" >&2
    exit 1
  fi
  running_before="${previous_image}"
  previous_ids="$(describe_group | group_consumer_ids)"
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
    docker stop feelm-foldin-consumer >/dev/null 2>&1 || true
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
  "${compose[@]}" up -d --no-deps --force-recreate consumer; then
  restore_previous_image "Candidate Consumer deployment command failed"
fi

if ! wait_for_candidate "${previous_ids}"; then
  restore_previous_image "Candidate Consumer runtime verification failed"
fi

running_image="$(docker inspect --format '{{.Config.Image}}' feelm-foldin-consumer)"
if [[ "${running_image}" != "${CANDIDATE_IMAGE}" ]]; then
  restore_previous_image "Running Consumer image does not match candidate image"
fi

if ! verify_current_commit; then
  restore_previous_image "Candidate deployment became stale during runtime verification"
fi

if ! write_last_known_good "${CANDIDATE_IMAGE}"; then
  restore_previous_image "Could not persist the verified Consumer image"
fi

echo "Consumer deployment completed: ${running_image}"
