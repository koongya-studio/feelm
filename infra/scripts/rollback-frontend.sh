#!/usr/bin/env bash

set -Eeuo pipefail

TARGET_IMAGE="${1:?Usage: rollback-frontend.sh <target-image>}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
PUBLIC_BASE_URL="${FRONTEND_PUBLIC_BASE_URL:-https://j15e106.p.ssafy.io/app/}"
CURL_BIN="${CURL_BIN:-curl}"

if [[ "${PUBLIC_BASE_URL}" != */ ]]; then
  echo "FRONTEND_PUBLIC_BASE_URL must end with /" >&2
  exit 2
fi

compose=(docker compose)

if [[ -n "${COMPOSE_ENV_FILE:-}" ]]; then
  compose+=(--env-file "${COMPOSE_ENV_FILE}")
fi

compose+=(
  -f "${PROJECT_ROOT}/infra/compose/service.yml"
)

backend_image="${BACKEND_IMAGE:-}"
if [[ -z "${backend_image}" ]]; then
  backend_image="$(
    docker inspect --format '{{.Config.Image}}' feelm-backend 2>/dev/null || true
  )"
fi

if [[ -z "${backend_image}" ]]; then
  echo "BACKEND_IMAGE is not set and the running feelm-backend image could not be determined" >&2
  exit 1
fi

wait_for_healthy() {
  local attempts="${DEPLOY_HEALTH_ATTEMPTS:-24}"

  for ((i = 1; i <= attempts; i++)); do
    status="$(
      docker inspect \
        --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' \
        feelm-frontend 2>/dev/null || true
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

echo "Rolling back frontend to ${TARGET_IMAGE}"
echo "Backend image for Compose: ${backend_image}"

if ! docker image inspect "${TARGET_IMAGE}" >/dev/null 2>&1; then
  docker pull "${TARGET_IMAGE}"
fi

BACKEND_IMAGE="${backend_image}" \
  FRONTEND_IMAGE="${TARGET_IMAGE}" \
  "${compose[@]}" up -d --no-deps --force-recreate frontend

if ! wait_for_healthy; then
  echo "Rollback health check failed" >&2
  docker logs --tail 100 feelm-frontend >&2
  exit 1
fi

if [[ "${RECREATE_FRONTEND_NGINX:-1}" == "1" ]]; then
  BACKEND_IMAGE="${backend_image}" \
    FRONTEND_IMAGE="${TARGET_IMAGE}" \
    "${compose[@]}" up -d --no-deps --force-recreate nginx

  health_response="$(
    "${CURL_BIN}" --fail --silent --show-error "${PUBLIC_BASE_URL}healthz"
  )"
  if [[ "${health_response}" != "ok" ]]; then
    echo "Rollback public health response is invalid" >&2
    exit 1
  fi
fi

running_image="$(docker inspect --format '{{.Config.Image}}' feelm-frontend)"

if [[ "${running_image}" != "${TARGET_IMAGE}" ]]; then
  echo "Running image does not match rollback target" >&2
  exit 1
fi

echo "Rollback completed: ${running_image}"
