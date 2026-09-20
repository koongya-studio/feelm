#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
PYTHON_BIN="${PYTHON_BIN:-python3}"
mode="${1:-deploy}"
if [[ "${mode}" != "deploy" && "${mode}" != "--recover" ]]; then
  echo "Usage: deploy-distributed-observability.sh [--recover]" >&2
  exit 2
fi

: "${DISTRIBUTED_NODE_IP:?DISTRIBUTED_NODE_IP is required}"
services=(kafka-exporter kafka-ui node-exporter)
containers=(feelm-kafka-exporter feelm-kafka-ui feelm-node-exporter-distributed)
deployment_revision="${CI_COMMIT_SHA:-manual}"
export OBSERVABILITY_DEPLOY_REVISION="${deployment_revision}"

verify() {
  "${PYTHON_BIN}" -I -B "${SCRIPT_DIR}/verify-distributed-observability.py" \
    --host "${DISTRIBUTED_NODE_IP}" \
    --attempts "${DISTRIBUTED_OBSERVABILITY_HEALTH_ATTEMPTS:-24}" \
    --interval "${DISTRIBUTED_OBSERVABILITY_HEALTH_INTERVAL_SECONDS:-5}"
}

verify_candidate_revision() {
  local container actual_revision
  for container in "${containers[@]}"; do
    actual_revision="$(docker inspect "${container}" \
      --format '{{ index .Config.Labels "io.feelm.distributed-observability.revision" }}')"
    if [[ "${actual_revision}" != "${deployment_revision}" ]]; then
      echo "Distributed observability revision mismatch: ${container}" >&2
      return 1
    fi
  done
}

container_exists() {
  docker inspect "$1" >/dev/null 2>&1
}

if [[ "${mode}" == "--recover" ]]; then
  recoverable=()
  for container in "${containers[@]}"; do
    if container_exists "${container}-rollback"; then
      recoverable+=("${container}")
    fi
  done
  if ((${#recoverable[@]} == 0)); then
    echo "No distributed observability rollback containers are available" >&2
    exit 1
  fi
  for container in "${recoverable[@]}"; do
    docker rm -f "${container}" >/dev/null 2>&1 || true
    docker rename "${container}-rollback" "${container}"
  done
  docker start "${recoverable[@]}" >/dev/null
  if ! verify; then
    echo "Recovered distributed observability containers did not pass verification" >&2
    exit 1
  fi
  echo "Previous distributed observability containers recovered: ${recoverable[*]}"
  exit 0
fi

compose=(
  docker compose
  --project-name "feelm-distributed-observability-${CI_PIPELINE_ID:-manual-$$}"
)
if [[ -n "${COMPOSE_ENV_FILE:-}" ]]; then
  if [[ ! -f "${COMPOSE_ENV_FILE}" ]]; then
    echo "COMPOSE_ENV_FILE does not exist" >&2
    exit 2
  fi
  compose+=(--env-file "${COMPOSE_ENV_FILE}")
fi
compose+=(-f "${PROJECT_ROOT}/infra/compose/distributed.yml")

for container in "${containers[@]}"; do
  if container_exists "${container}-rollback"; then
    echo "Stale distributed observability rollback container exists: ${container}-rollback" >&2
    echo "Run deploy-distributed-observability.sh --recover before retrying" >&2
    exit 1
  fi
done

"${compose[@]}" config --quiet
"${compose[@]}" pull --quiet "${services[@]}"

existing=()
for container in "${containers[@]}"; do
  if container_exists "${container}"; then
    existing+=("${container}")
  fi
done

if ((${#existing[@]})) && ! docker stop "${existing[@]}" >/dev/null; then
  docker start "${existing[@]}" >/dev/null 2>&1 || true
  echo "Could not stop existing distributed observability containers" >&2
  exit 1
fi

renamed=()
for container in "${existing[@]}"; do
  if ! docker rename "${container}" "${container}-rollback"; then
    for renamed_container in "${renamed[@]}"; do
      docker rename "${renamed_container}-rollback" "${renamed_container}" >/dev/null 2>&1 || true
    done
    docker start "${existing[@]}" >/dev/null 2>&1 || true
    echo "Could not preserve existing distributed observability containers" >&2
    exit 1
  fi
  renamed+=("${container}")
done

restore_previous() {
  local reason="$1"
  local restore_failed=0
  echo "${reason}" >&2
  for container in "${containers[@]}"; do
    docker rm -f "${container}" >/dev/null 2>&1 || true
  done
  for container in "${renamed[@]}"; do
    docker rename "${container}-rollback" "${container}" || restore_failed=1
  done
  if ((${#renamed[@]})) && ! docker start "${renamed[@]}" >/dev/null; then
    restore_failed=1
  fi
  if ((restore_failed == 0)) && ((${#renamed[@]})) && verify; then
    echo "Candidate deployment failed; previous distributed observability" \
      "containers were restored" >&2
  elif ((${#renamed[@]} == 0)); then
    echo "Candidate deployment failed and no previous distributed observability" \
      "containers existed" >&2
  else
    echo "Distributed observability rollback failed; manual recovery is required" >&2
  fi
  return 1
}

if ! "${compose[@]}" up -d --no-deps --force-recreate "${services[@]}"; then
  restore_previous "Distributed observability candidate deployment command failed"
fi
if ! verify || ! verify_candidate_revision; then
  restore_previous "Distributed observability candidate verification failed"
fi

for container in "${renamed[@]}"; do
  if ! docker rm "${container}-rollback" >/dev/null; then
    echo "Distributed observability is healthy, but rollback cleanup requires manual recovery" >&2
    exit 1
  fi
done
echo "Distributed observability deployment completed: ${services[*]}"
