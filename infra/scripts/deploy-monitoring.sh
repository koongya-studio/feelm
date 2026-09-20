#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
PYTHON_BIN="${PYTHON_BIN:-python3}"
mode="${1:-deploy}"
if [[ "${mode}" != "deploy" && "${mode}" != "--recover" ]]; then
  echo "Usage: deploy-monitoring.sh [--recover]" >&2
  exit 2
fi

services=(loki alloy prometheus grafana mattermost-alert-relay node-exporter postgres-exporter redis-exporter)
containers=(
  feelm-loki
  feelm-alloy
  feelm-prometheus
  feelm-grafana
  feelm-mattermost-alert-relay
  feelm-node-exporter
  feelm-postgres-exporter
  feelm-redis-exporter
)
deployment_revision="${CI_COMMIT_SHA:-manual}"
export MONITORING_DEPLOY_REVISION="${deployment_revision}"

verify() {
  local log_pipeline_mode="${1:-full}"
  local args=(
    "${PYTHON_BIN}" -I -B "${SCRIPT_DIR}/verify-monitoring-deployment.py"
    --attempts "${MONITORING_HEALTH_ATTEMPTS:-24}"
    --interval "${MONITORING_HEALTH_INTERVAL_SECONDS:-5}"
    --loki-url "http://127.0.0.1:${LOKI_PORT:-3100}"
    --alloy-url "http://127.0.0.1:${ALLOY_PORT:-12345}"
    --relay-url "http://127.0.0.1:${GRAFANA_ALERT_RELAY_PORT:-9087}"
  )
  if [[ "${log_pipeline_mode}" == "legacy" ]]; then
    args+=(--skip-log-pipeline)
  fi
  "${args[@]}"
}

verify_candidate_revision() {
  local container actual_revision
  for container in "${containers[@]}"; do
    actual_revision="$(docker inspect "${container}" \
      --format '{{ index .Config.Labels "io.feelm.monitoring.revision" }}')"
    if [[ "${actual_revision}" != "${deployment_revision}" ]]; then
      echo "Monitoring deployment revision mismatch: ${container}" >&2
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
    echo "No monitoring rollback containers are available" >&2
    exit 1
  fi
  recovery_log_pipeline="full"
  if ! container_exists "feelm-loki-rollback" && ! container_exists "feelm-alloy-rollback"; then
    recovery_log_pipeline="legacy"
  elif ! container_exists "feelm-loki-rollback" || ! container_exists "feelm-alloy-rollback"; then
    echo "Incomplete Loki/Alloy rollback pair; manual recovery is required" >&2
    exit 1
  fi
  if [[ "${recovery_log_pipeline}" == "legacy" ]]; then
    docker rm -f feelm-loki feelm-alloy >/dev/null 2>&1 || true
  fi
  for container in "${recoverable[@]}"; do
    docker rm -f "${container}" >/dev/null 2>&1 || true
    docker rename "${container}-rollback" "${container}"
  done
  docker start "${recoverable[@]}" >/dev/null
  if ! verify "${recovery_log_pipeline}"; then
    echo "Recovered monitoring containers did not pass verification" >&2
    exit 1
  fi
  echo "Previous monitoring containers recovered: ${recoverable[*]}"
  exit 0
fi

compose_project="feelm-monitoring-${CI_PIPELINE_ID:-manual-$$}"
compose=(docker compose --project-name "${compose_project}")
if [[ -n "${COMPOSE_ENV_FILE:-}" ]]; then
  if [[ ! -f "${COMPOSE_ENV_FILE}" ]]; then
    echo "COMPOSE_ENV_FILE does not exist" >&2
    exit 2
  fi
  compose+=(--env-file "${COMPOSE_ENV_FILE}")
fi
compose+=(-f "${PROJECT_ROOT}/infra/compose/monitoring.yml")

for container in "${containers[@]}"; do
  if container_exists "${container}-rollback"; then
    echo "Stale monitoring rollback container exists: ${container}-rollback" >&2
    echo "Run deploy-monitoring.sh --recover before retrying" >&2
    exit 1
  fi
done

previous_log_pipeline="full"
if ! container_exists "feelm-loki" && ! container_exists "feelm-alloy"; then
  previous_log_pipeline="legacy"
elif ! container_exists "feelm-loki" || ! container_exists "feelm-alloy"; then
  echo "Incomplete Loki/Alloy container pair; manual recovery is required" >&2
  exit 1
fi

"${compose[@]}" config --quiet
"${compose[@]}" pull --quiet "${services[@]}"

existing=()
for container in "${containers[@]}"; do
  if container_exists "${container}"; then
    existing+=("${container}")
  fi
done

if ((${#existing[@]})); then
  if ! docker stop "${existing[@]}" >/dev/null; then
    docker start "${existing[@]}" >/dev/null 2>&1 || true
    echo "Could not stop existing monitoring containers; candidate was not started" >&2
    exit 1
  fi
fi

renamed=()
for container in "${existing[@]}"; do
  if ! docker rename "${container}" "${container}-rollback"; then
    for renamed_container in "${renamed[@]}"; do
      docker rename "${renamed_container}-rollback" "${renamed_container}" >/dev/null 2>&1 || true
    done
    docker start "${existing[@]}" >/dev/null 2>&1 || true
    echo "Could not preserve existing monitoring containers; candidate was not started" >&2
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
    if ! docker rename "${container}-rollback" "${container}"; then
      restore_failed=1
    fi
  done
  if ((${#renamed[@]})) && ! docker start "${renamed[@]}" >/dev/null; then
    restore_failed=1
  fi
  if ((restore_failed == 0)) && ((${#renamed[@]})) && verify "${previous_log_pipeline}"; then
    echo "Candidate deployment failed; previous monitoring containers were restored" >&2
    return 1
  fi
  if ((${#renamed[@]} == 0)); then
    echo "Candidate deployment failed and no previous monitoring containers existed" >&2
  else
    echo "Monitoring rollback failed; manual recovery is required" >&2
  fi
  return 1
}

if ! "${compose[@]}" up -d --no-deps --force-recreate "${services[@]}"; then
  restore_previous "Monitoring candidate deployment command failed"
fi

if ! verify || ! verify_candidate_revision; then
  restore_previous "Monitoring candidate verification failed"
fi

cleanup_failed=0
for container in "${renamed[@]}"; do
  if ! docker rm "${container}-rollback" >/dev/null; then
    cleanup_failed=1
  fi
done
if ((cleanup_failed)); then
  echo "Monitoring is healthy, but rollback container cleanup requires manual recovery" >&2
  exit 1
fi

echo "Monitoring deployment completed: ${services[*]}"
