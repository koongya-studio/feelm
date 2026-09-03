#!/usr/bin/env bash

set -Eeuo pipefail

TARGET_IMAGE="${1:?Usage: rollback-backend.sh <target-image>}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

compose=(docker compose)

if [[ -n "${COMPOSE_ENV_FILE:-}" ]]; then
  compose+=(--env-file "${COMPOSE_ENV_FILE}")
fi

compose+=(
  -f "${PROJECT_ROOT}/infra/compose/service.yml"
)

wait_for_healthy() {
  local attempts="${DEPLOY_HEALTH_ATTEMPTS:-24}"

  for ((i = 1; i <= attempts; i++)); do
    status="$(
      docker inspect \
        --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' \
        feelm-backend 2>/dev/null || true
    )"

    if [[ "${status}" == "healthy" ]]; then
      return 0
    fi

    if [[ "${status}" == "unhealthy" ]]; then
      return 1
    fi

    sleep 5
  done

  return 1
}

echo "Rolling back backend to ${TARGET_IMAGE}"

if ! docker image inspect "${TARGET_IMAGE}" >/dev/null 2>&1; then
  docker pull "${TARGET_IMAGE}"
fi

BACKEND_IMAGE="${TARGET_IMAGE}" \
  "${compose[@]}" up -d --no-deps --force-recreate backend

if ! wait_for_healthy; then
  echo "Rollback health check failed" >&2
  docker logs --tail 100 feelm-backend >&2
  exit 1
fi

curl --fail --silent --show-error \
  http://127.0.0.1:8080/actuator/health >/dev/null

running_image="$(
  docker inspect --format '{{.Config.Image}}' feelm-backend
)"

if [[ "${running_image}" != "${TARGET_IMAGE}" ]]; then
  echo "Running image does not match rollback target" >&2
  exit 1
fi

echo "Rollback completed: ${running_image}"