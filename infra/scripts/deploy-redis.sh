#!/usr/bin/env bash

set -Eeuo pipefail

CANDIDATE_REVISION="${1:?Usage: deploy-redis.sh <candidate-revision>}"

if [[ ! "${CANDIDATE_REVISION}" =~ ^[0-9a-f]{7,64}$ ]]; then
  echo "Redis candidate revision must be a hexadecimal commit identifier" >&2
  exit 2
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
SOURCE_CONFIG_DIR="${PROJECT_ROOT}/infra/redis"
RELEASE_ROOT="${REDIS_CONFIG_RELEASE_ROOT:-/var/lib/feelm-deploy/redis-config}"
CANDIDATE_DIR="${RELEASE_ROOT}/releases/${CANDIDATE_REVISION}"
CONFIG_VERIFY_SCRIPT="${REDIS_CONFIG_VERIFY_SCRIPT:-${SCRIPT_DIR}/verify-datastore-config.sh}"
RUNTIME_VERIFY_SCRIPT="${REDIS_RUNTIME_VERIFY_SCRIPT:-${SCRIPT_DIR}/verify-datastores.sh}"
CONFIG_FILES=(start.sh readonly.acl local-backend.acl)

compose=(docker compose)
if [[ -n "${COMPOSE_ENV_FILE:-}" ]]; then
  compose+=(--env-file "${COMPOSE_ENV_FILE}")
fi
compose+=(-f "${PROJECT_ROOT}/infra/compose/service.yml")

mounted_source() {
  local destination="$1"
  docker inspect --format \
    "{{range .Mounts}}{{if eq .Destination \"${destination}\"}}{{.Source}}{{end}}{{end}}" \
    feelm-redis 2>/dev/null || true
}

verify_config_directory() {
  local directory="$1"
  local file
  for file in "${CONFIG_FILES[@]}"; do
    [[ -f "${directory}/${file}" ]] || return 1
  done
}

snapshot_running_config() {
  local snapshot_dir="${RELEASE_ROOT}/releases/bootstrap-${CANDIDATE_REVISION}"
  local temporary="${snapshot_dir}.tmp.$$"

  if [[ -d "${snapshot_dir}" ]]; then
    verify_config_directory "${snapshot_dir}" || {
      echo "Redis bootstrap snapshot is incomplete" >&2
      return 1
    }
    PREVIOUS_CONFIG_DIR="${snapshot_dir}"
    return 0
  fi

  umask 077
  mkdir -p "${RELEASE_ROOT}/releases"
  mkdir "${temporary}"
  if ! docker cp feelm-redis:/usr/local/etc/redis/start.sh "${temporary}/start.sh" \
    || ! docker cp feelm-redis:/usr/local/etc/redis/readonly.acl "${temporary}/readonly.acl"; then
    rm -rf "${temporary}"
    echo "Running Redis configuration could not be preserved for rollback" >&2
    return 1
  fi
  if ! docker cp feelm-redis:/usr/local/etc/redis/local-backend.acl \
    "${temporary}/local-backend.acl" 2>/dev/null; then
    install -m 0444 "${SOURCE_CONFIG_DIR}/local-backend.acl" \
      "${temporary}/local-backend.acl"
  fi
  verify_config_directory "${temporary}" || {
    rm -rf "${temporary}"
    echo "Running Redis configuration could not be preserved for rollback" >&2
    return 1
  }
  chmod 0444 "${temporary}/start.sh" "${temporary}/readonly.acl" \
    "${temporary}/local-backend.acl"
  mv "${temporary}" "${snapshot_dir}"
  PREVIOUS_CONFIG_DIR="${snapshot_dir}"
}

install_candidate() {
  local temporary file

  mkdir -p "${RELEASE_ROOT}/releases"
  if [[ -d "${CANDIDATE_DIR}" ]]; then
    for file in "${CONFIG_FILES[@]}"; do
      cmp -s "${SOURCE_CONFIG_DIR}/${file}" "${CANDIDATE_DIR}/${file}" || {
          echo "Redis candidate release already exists with different content" >&2
          return 1
        }
    done
    return 0
  fi

  temporary="${CANDIDATE_DIR}.tmp.$$"
  umask 077
  mkdir "${temporary}"
  for file in "${CONFIG_FILES[@]}"; do
    install -m 0444 "${SOURCE_CONFIG_DIR}/${file}" "${temporary}/${file}"
  done
  mv "${temporary}" "${CANDIDATE_DIR}"
}

recreate_redis() {
  local config_dir="$1"
  REDIS_CONFIG_DIR="${config_dir}" BACKEND_IMAGE="${BACKEND_IMAGE}" \
    "${compose[@]}" up -d --no-deps --force-recreate redis
}

wait_for_healthy() {
  local attempts="${DEPLOY_HEALTH_ATTEMPTS:-24}"
  local interval="${DEPLOY_HEALTH_INTERVAL_SECONDS:-5}"
  local status="none"
  local i

  for ((i = 1; i <= attempts; i++)); do
    status="$(
      docker inspect \
        --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' \
        feelm-redis 2>/dev/null || true
    )"

    if [[ "${status}" == "healthy" ]]; then
      return 0
    fi

    if [[ "${status}" == "unhealthy" ]]; then
      echo "feelm-redis is unhealthy" >&2
      return 1
    fi

    if ((i < attempts)); then
      echo "feelm-redis is not ready: ${status}; retrying (${i}/${attempts})" >&2
      sleep "${interval}"
    fi
  done

  echo "feelm-redis did not become healthy: ${status}" >&2
  return 1
}

verify_runtime() {
  wait_for_healthy && bash "${RUNTIME_VERIFY_SCRIPT}"
}

restore_previous_config() {
  local reason="$1"

  echo "${reason}" >&2
  if ! recreate_redis "${PREVIOUS_CONFIG_DIR}" || ! verify_runtime; then
    echo "Redis configuration rollback failed; manual recovery is required" >&2
    return 1
  fi
  echo "Redis configuration deployment failed; previous configuration was restored" >&2
  return 1
}

if [[ ! -d "${RELEASE_ROOT}" || ! -w "${RELEASE_ROOT}" ]]; then
  echo "Redis configuration release root is unavailable" >&2
  exit 1
fi

start_source="$(mounted_source /usr/local/etc/redis/start.sh)"
acl_source="$(mounted_source /usr/local/etc/redis/readonly.acl)"
local_backend_acl_source="$(mounted_source /usr/local/etc/redis/local-backend.acl)"
PREVIOUS_CONFIG_DIR="$(dirname "${start_source}")"
if [[ -z "${start_source}" || -z "${acl_source}" \
  || -z "${local_backend_acl_source}" \
  || "$(dirname "${acl_source}")" != "${PREVIOUS_CONFIG_DIR}" \
  || "$(dirname "${local_backend_acl_source}")" != "${PREVIOUS_CONFIG_DIR}" ]] \
  || ! verify_config_directory "${PREVIOUS_CONFIG_DIR}"; then
  echo "Running Redis configuration mounts are inconsistent; preserving container files" >&2
  snapshot_running_config
fi

BACKEND_IMAGE="${BACKEND_IMAGE:-$(
  docker inspect --format '{{.Config.Image}}' feelm-backend 2>/dev/null || true
)}"
if [[ -z "${BACKEND_IMAGE}" ]]; then
  echo "Running backend image could not be identified" >&2
  exit 1
fi

install_candidate
REDIS_CONFIG_DIR="${CANDIDATE_DIR}" bash "${CONFIG_VERIFY_SCRIPT}"

if ! recreate_redis "${CANDIDATE_DIR}"; then
  restore_previous_config "Redis configuration deployment command failed"
fi
if ! verify_runtime; then
  restore_previous_config "Redis configuration runtime verification failed"
fi

running_start_source="$(mounted_source /usr/local/etc/redis/start.sh)"
running_readonly_acl_source="$(mounted_source /usr/local/etc/redis/readonly.acl)"
running_local_backend_acl_source="$(mounted_source /usr/local/etc/redis/local-backend.acl)"
if [[ "${running_start_source}" != "${CANDIDATE_DIR}/start.sh" \
  || "${running_readonly_acl_source}" != "${CANDIDATE_DIR}/readonly.acl" \
  || "${running_local_backend_acl_source}" != "${CANDIDATE_DIR}/local-backend.acl" ]]; then
  restore_previous_config "Running Redis configuration does not match the candidate release"
fi

echo "Redis configuration deployment completed: ${CANDIDATE_REVISION}"
