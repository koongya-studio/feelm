#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
PYTHON_BIN="${PYTHON_BIN:-python3}"
mode="${1:-deploy}"
if [[ "${mode}" != "deploy" && "${mode}" != "--recover" ]]; then
  echo "Usage: deploy-spark-history.sh [--recover]" >&2
  exit 2
fi

: "${DISTRIBUTED_NODE_IP:?DISTRIBUTED_NODE_IP is required}"
container=feelm-spark-history-server
deployment_revision="${CI_COMMIT_SHA:-manual}"
export SPARK_HISTORY_DEPLOY_REVISION="${deployment_revision}"

verify() {
  "${PYTHON_BIN}" -I -B "${SCRIPT_DIR}/verify-spark-history.py" \
    --host "${DISTRIBUTED_NODE_IP}" \
    --attempts "${SPARK_HISTORY_HEALTH_ATTEMPTS:-24}" \
    --interval "${SPARK_HISTORY_HEALTH_INTERVAL_SECONDS:-5}"
}

verify_candidate_revision() {
  local actual_revision
  actual_revision="$(docker inspect "${container}" \
    --format '{{ index .Config.Labels "io.feelm.spark-history.revision" }}')"
  if [[ "${actual_revision}" != "${deployment_revision}" ]]; then
    echo "Spark History deployment revision mismatch" >&2
    return 1
  fi
}

container_exists() {
  docker inspect "$1" >/dev/null 2>&1
}

if [[ "${mode}" == "--recover" ]]; then
  if ! container_exists "${container}-rollback"; then
    echo "No Spark History rollback container is available" >&2
    exit 1
  fi
  docker rm -f "${container}" >/dev/null 2>&1 || true
  docker rename "${container}-rollback" "${container}"
  docker start "${container}" >/dev/null
  if ! verify; then
    echo "Recovered Spark History container did not pass verification" >&2
    exit 1
  fi
  echo "Previous Spark History container recovered"
  exit 0
fi

if container_exists "${container}-rollback"; then
  echo "Stale Spark History rollback container exists: ${container}-rollback" >&2
  echo "Run deploy-spark-history.sh --recover before retrying" >&2
  exit 1
fi

if ! SPARK_IMAGE="$(docker inspect feelm-spark-master --format '{{.Config.Image}}')" \
  || [[ -z "${SPARK_IMAGE}" ]]; then
  echo "Running Spark image could not be resolved; History deployment refused" >&2
  exit 1
fi
export SPARK_IMAGE

compose=(docker compose --project-name "feelm-spark-history-${CI_PIPELINE_ID:-manual-$$}")
if [[ -n "${COMPOSE_ENV_FILE:-}" ]]; then
  if [[ ! -f "${COMPOSE_ENV_FILE}" ]]; then
    echo "COMPOSE_ENV_FILE does not exist" >&2
    exit 2
  fi
  compose+=(--env-file "${COMPOSE_ENV_FILE}")
fi
compose+=(-f "${PROJECT_ROOT}/infra/compose/distributed.yml")

"${compose[@]}" config --quiet
"${compose[@]}" pull --quiet spark-history-init
"${compose[@]}" --profile init run --rm --no-deps spark-history-init

had_previous=false
if container_exists "${container}"; then
  had_previous=true
  docker stop "${container}" >/dev/null
  if ! docker rename "${container}" "${container}-rollback"; then
    docker start "${container}" >/dev/null 2>&1 || true
    echo "Could not preserve existing Spark History container" >&2
    exit 1
  fi
fi

restore_previous() {
  local reason="$1"
  echo "${reason}" >&2
  docker rm -f "${container}" >/dev/null 2>&1 || true
  if [[ "${had_previous}" == true ]] \
    && docker rename "${container}-rollback" "${container}" \
    && docker start "${container}" >/dev/null \
    && verify; then
    echo "Candidate deployment failed; previous Spark History container was restored" >&2
  elif [[ "${had_previous}" == false ]]; then
    echo "Candidate deployment failed and no previous Spark History container existed" >&2
  else
    echo "Spark History rollback failed; manual recovery is required" >&2
  fi
  return 1
}

if ! "${compose[@]}" up -d --no-deps --force-recreate spark-history-server; then
  restore_previous "Spark History candidate deployment command failed"
fi
if ! verify || ! verify_candidate_revision; then
  restore_previous "Spark History candidate verification failed"
fi
if [[ "${had_previous}" == true ]] && ! docker rm "${container}-rollback" >/dev/null; then
  echo "Spark History is healthy, but rollback cleanup requires manual recovery" >&2
  exit 1
fi
echo "Spark History deployment completed"
