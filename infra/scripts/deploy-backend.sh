#!/usr/bin/env bash

set -Eeuo pipefail

CANDIDATE_IMAGE="${1:?Usage: deploy-backend.sh <candidate-image>}"

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

previous_image="$(
  docker inspect --format '{{.Config.Image}}' feelm-backend 2>/dev/null || true
)"

echo "Previous backend image: ${previous_image:-none}"
echo "Candidate backend image: ${CANDIDATE_IMAGE}"

if ! docker image inspect "${CANDIDATE_IMAGE}" >/dev/null 2>&1; then
  docker pull "${CANDIDATE_IMAGE}"
fi

BACKEND_IMAGE="${CANDIDATE_IMAGE}" \
  "${compose[@]}" up -d --no-deps --force-recreate backend

if wait_for_healthy \
  && curl --fail --silent --show-error \
    http://127.0.0.1:8080/actuator/health >/dev/null; then
  running_image="$(
    docker inspect --format '{{.Config.Image}}' feelm-backend
  )"

  if [[ "${running_image}" != "${CANDIDATE_IMAGE}" ]]; then
    echo "Running image does not match candidate image" >&2
    exit 1
  fi

  echo "Deployment completed: ${running_image}"
  exit 0
fi

echo "Deployment health check failed" >&2
docker logs --tail 100 feelm-backend >&2

if [[ -z "${previous_image}" ]]; then
  echo "No previous image is available for rollback" >&2
  docker stop feelm-backend >/dev/null 2>&1 || true
  exit 1
fi

echo "Restoring previous image: ${previous_image}" >&2

COMPOSE_ENV_FILE="${COMPOSE_ENV_FILE:-}" \
  "${SCRIPT_DIR}/rollback-backend.sh" "${previous_image}"

echo "Candidate deployment failed; previous image was restored" >&2
exit 1