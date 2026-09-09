#!/usr/bin/env bash

set -Eeuo pipefail

CANDIDATE_IMAGE="${1:?Usage: deploy-frontend.sh <candidate-image>}"

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

check_public_frontend() {
  local health_response

  health_response="$(
    "${CURL_BIN}" --fail --silent --show-error "${PUBLIC_BASE_URL}healthz"
  )" || return 1

  [[ "${health_response}" == "ok" ]] \
    && "${CURL_BIN}" --fail --silent --show-error "${PUBLIC_BASE_URL}" >/dev/null \
    && "${CURL_BIN}" --fail --silent --show-error "${PUBLIC_BASE_URL}movies/1" >/dev/null
}

previous_image="$(
  docker inspect --format '{{.Config.Image}}' feelm-frontend 2>/dev/null || true
)"
nginx_replaced=0

echo "Previous frontend image: ${previous_image:-none}"
echo "Candidate frontend image: ${CANDIDATE_IMAGE}"

if ! docker image inspect "${CANDIDATE_IMAGE}" >/dev/null 2>&1; then
  if ! docker pull "${CANDIDATE_IMAGE}"; then
    echo "Candidate image pull failed; running service was not changed" >&2
    exit 1
  fi
fi

restore_previous_image() {
  local reason="$1"

  echo "${reason}" >&2
  docker logs --tail 100 feelm-frontend >&2 || true

  if [[ -z "${previous_image}" ]]; then
    echo "No previous image is available for rollback" >&2
    docker stop feelm-frontend >/dev/null 2>&1 || true
    return 1
  fi

  echo "Restoring previous image: ${previous_image}" >&2

  if ! COMPOSE_ENV_FILE="${COMPOSE_ENV_FILE:-}" \
    FRONTEND_PUBLIC_BASE_URL="${PUBLIC_BASE_URL}" \
    RECREATE_FRONTEND_NGINX="${nginx_replaced}" \
    bash "${SCRIPT_DIR}/rollback-frontend.sh" "${previous_image}"; then
    echo "Rollback failed; manual recovery is required" >&2
    return 1
  fi

  echo "Candidate deployment failed; previous image was restored" >&2
  return 1
}

if ! FRONTEND_IMAGE="${CANDIDATE_IMAGE}" \
  "${compose[@]}" up -d --no-deps --force-recreate frontend; then
  restore_previous_image "Candidate deployment command failed"
fi

if ! wait_for_healthy; then
  restore_previous_image "Deployment health check failed"
fi

if ! FRONTEND_IMAGE="${CANDIDATE_IMAGE}" \
  "${compose[@]}" run --rm --no-deps nginx nginx -t; then
  restore_previous_image "Nginx configuration check failed"
fi

nginx_replaced=1
if ! FRONTEND_IMAGE="${CANDIDATE_IMAGE}" \
  "${compose[@]}" up -d --no-deps --force-recreate nginx; then
  restore_previous_image "Nginx deployment command failed"
fi

if check_public_frontend; then
  running_image="$(docker inspect --format '{{.Config.Image}}' feelm-frontend)"

  if [[ "${running_image}" != "${CANDIDATE_IMAGE}" ]]; then
    restore_previous_image "Running image does not match candidate image"
  fi

  echo "Deployment completed: ${running_image}"
  exit 0
fi

restore_previous_image "Public frontend health check failed"
