#!/usr/bin/env bash

set -Eeuo pipefail

CANDIDATE_REVISION="${1:?Usage: deploy-postgresql.sh <candidate-revision>}"

if [[ ! "${CANDIDATE_REVISION}" =~ ^[0-9a-f]{7,64}$ ]]; then
  echo "PostgreSQL candidate revision must be a hexadecimal commit identifier" >&2
  exit 2
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
BACKUP_ROOT="${POSTGRES_BACKUP_ROOT:-/var/lib/feelm-deploy/postgresql/backups}"
BACKUP_DIR="${BACKUP_ROOT}/${CANDIDATE_REVISION}"
RUNTIME_VERIFY_SCRIPT="${POSTGRES_RUNTIME_VERIFY_SCRIPT:-${SCRIPT_DIR}/verify-postgresql-deployment.sh}"
PYTHON_BIN="${PYTHON_BIN:-python3}"
OVERRIDE_FILE=""

CANDIDATE_COMPOSE_FILE="${PROJECT_ROOT}/infra/compose/service.yml"

compose_command() {
  local compose_file="$1"
  COMPOSE_COMMAND=(docker compose)
  if [[ -n "${COMPOSE_ENV_FILE:-}" ]]; then
    COMPOSE_COMMAND+=(--env-file "${COMPOSE_ENV_FILE}")
  fi
  COMPOSE_COMMAND+=(
    --project-directory "${PROJECT_ROOT}/infra/compose"
    -f "${compose_file}"
  )
}

cleanup() {
  if [[ -n "${OVERRIDE_FILE}" ]]; then
    rm -f "${OVERRIDE_FILE}"
  fi
}
trap cleanup EXIT

container_image() {
  docker inspect --format '{{.Config.Image}}' "$1"
}

postgres_volume() {
  docker inspect --format \
    '{{range .Mounts}}{{if eq .Destination "/var/lib/postgresql/data"}}{{if eq .Type "volume"}}{{.Name}}{{end}}{{end}}{{end}}' \
    feelm-postgres
}

wait_for_healthy() {
  local attempts="${DEPLOY_HEALTH_ATTEMPTS:-24}"
  local interval="${DEPLOY_HEALTH_INTERVAL_SECONDS:-5}"
  local status="none"
  local i

  for ((i = 1; i <= attempts; i++)); do
    status="$(docker inspect --format \
      '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' \
      feelm-postgres 2>/dev/null || true)"
    if [[ "${status}" == "healthy" ]]; then
      return 0
    fi
    if [[ "${status}" == "unhealthy" ]]; then
      echo "feelm-postgres is unhealthy" >&2
      return 1
    fi
    if ((i < attempts)); then
      echo "feelm-postgres is not ready: ${status}; retrying (${i}/${attempts})" >&2
      sleep "${interval}"
    fi
  done

  echo "feelm-postgres did not become healthy: ${status}" >&2
  return 1
}

verify_runtime() {
  wait_for_healthy && bash "${RUNTIME_VERIFY_SCRIPT}"
}

recreate_postgresql() {
  local image="$1"
  local compose_file="$2"
  compose_command "${compose_file}"
  POSTGRES_DEPLOY_IMAGE="${image}" BACKEND_IMAGE="${BACKEND_IMAGE}" \
    "${COMPOSE_COMMAND[@]}" -f "${OVERRIDE_FILE}" \
    up -d --no-deps --force-recreate postgres
}

restore_previous() {
  local reason="$1"

  echo "${reason}" >&2
  if ! recreate_postgresql "${PREVIOUS_IMAGE}" "${BACKUP_DIR}/previous-service.yml" \
    || [[ "$(postgres_volume)" != "${POSTGRES_VOLUME}" ]] \
    || ! verify_runtime; then
    echo "PostgreSQL rollback failed; manual recovery is required. Backup: ${BACKUP_DIR}" >&2
    return 1
  fi
  echo "PostgreSQL deployment failed; previous image was restored with the original volume" >&2
  return 1
}

if [[ ! -d "${BACKUP_ROOT}" || ! -w "${BACKUP_ROOT}" ]]; then
  echo "PostgreSQL backup root is unavailable or not writable: ${BACKUP_ROOT}" >&2
  exit 1
fi
if [[ -e "${BACKUP_DIR}" ]]; then
  echo "PostgreSQL backup already exists for revision: ${CANDIDATE_REVISION}" >&2
  exit 1
fi

BACKEND_IMAGE="${BACKEND_IMAGE:-$(container_image feelm-backend 2>/dev/null || true)}"
PREVIOUS_IMAGE="$(container_image feelm-postgres 2>/dev/null || true)"
POSTGRES_VOLUME="$(postgres_volume 2>/dev/null || true)"
if [[ -z "${BACKEND_IMAGE}" || -z "${PREVIOUS_IMAGE}" ]]; then
  echo "Running backend and PostgreSQL images must be identifiable before deployment" >&2
  exit 1
fi
if [[ -z "${POSTGRES_VOLUME}" ]]; then
  echo "PostgreSQL data must use a named volume before deployment" >&2
  exit 1
fi

compose_command "${CANDIDATE_COMPOSE_FILE}"
"${COMPOSE_COMMAND[@]}" config --quiet
CANDIDATE_IMAGE="$("${COMPOSE_COMMAND[@]}" config --format json \
  | "${PYTHON_BIN}" -c 'import json, sys; print(json.load(sys.stdin)["services"]["postgres"]["image"])')"
if [[ -z "${CANDIDATE_IMAGE}" ]]; then
  echo "PostgreSQL candidate image could not be resolved from Compose" >&2
  exit 1
fi
"${COMPOSE_COMMAND[@]}" pull --quiet postgres

current_version_num="$(docker exec feelm-postgres sh -c \
  'psql -X -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc "SHOW server_version_num;"')"
current_version_num="${current_version_num//[[:space:]]/}"
candidate_version="$(docker run --rm --entrypoint postgres "${CANDIDATE_IMAGE}" --version)"
candidate_major="${candidate_version##* }"
candidate_major="${candidate_major%%.*}"
if [[ ! "${current_version_num}" =~ ^[0-9]+$ || ! "${candidate_major}" =~ ^[0-9]+$ ]]; then
  echo "PostgreSQL major versions could not be verified" >&2
  exit 1
fi
current_major="$((current_version_num / 10000))"
if [[ "${current_major}" != "${candidate_major}" ]]; then
  echo "PostgreSQL major version changes require a separately approved migration: ${current_major} -> ${candidate_major}" >&2
  exit 1
fi

temporary_backup="${BACKUP_DIR}.tmp.$$"
umask 077
mkdir "${temporary_backup}"
previous_compose_source="${POSTGRES_PREVIOUS_COMPOSE_FILE:-}"
if [[ -n "${previous_compose_source}" ]]; then
  if [[ ! -f "${previous_compose_source}" ]]; then
    rm -rf "${temporary_backup}"
    echo "Previous PostgreSQL Compose configuration is unavailable" >&2
    exit 1
  fi
  cp "${previous_compose_source}" "${temporary_backup}/previous-service.yml"
else
  previous_revision="${CI_COMMIT_BEFORE_SHA:-}"
  if [[ ! "${previous_revision}" =~ ^[0-9a-f]{40}$ ]] \
    || [[ "${previous_revision}" == "0000000000000000000000000000000000000000" ]] \
    || ! git -C "${PROJECT_ROOT}" show \
      "${previous_revision}:infra/compose/service.yml" \
      >"${temporary_backup}/previous-service.yml"; then
    rm -rf "${temporary_backup}"
    echo "Previous PostgreSQL Compose configuration could not be preserved" >&2
    exit 1
  fi
fi
cp "${CANDIDATE_COMPOSE_FILE}" "${temporary_backup}/candidate-service.yml"
compose_command "${temporary_backup}/previous-service.yml"
if ! "${COMPOSE_COMMAND[@]}" config --quiet; then
  rm -rf "${temporary_backup}"
  echo "Previous PostgreSQL Compose configuration is not usable for rollback" >&2
  exit 1
fi

if ! docker exec feelm-postgres sh -c \
    'pg_dump -Fc -U "$POSTGRES_USER" -d "$POSTGRES_DB"' \
    >"${temporary_backup}/database.dump" \
  || ! docker exec feelm-postgres sh -c \
    'pg_dumpall --globals-only -U "$POSTGRES_USER"' \
    >"${temporary_backup}/globals.sql" \
  || [[ ! -s "${temporary_backup}/database.dump" ]] \
  || [[ ! -s "${temporary_backup}/globals.sql" ]] \
  || ! docker exec -i feelm-postgres pg_restore --list \
    <"${temporary_backup}/database.dump" >/dev/null; then
  rm -rf "${temporary_backup}"
  echo "PostgreSQL backup or restore-readiness verification failed" >&2
  exit 1
fi

cat >"${temporary_backup}/manifest.txt" <<EOF
revision=${CANDIDATE_REVISION}
previous_image=${PREVIOUS_IMAGE}
candidate_image=${CANDIDATE_IMAGE}
volume=${POSTGRES_VOLUME}
previous_revision=${CI_COMMIT_BEFORE_SHA:-manual-snapshot}
EOF
chmod 0600 "${temporary_backup}/database.dump" \
  "${temporary_backup}/globals.sql" "${temporary_backup}/manifest.txt" \
  "${temporary_backup}/previous-service.yml" \
  "${temporary_backup}/candidate-service.yml"
mv "${temporary_backup}" "${BACKUP_DIR}"

OVERRIDE_FILE="$(mktemp)"
cat >"${OVERRIDE_FILE}" <<'EOF'
services:
  postgres:
    image: ${POSTGRES_DEPLOY_IMAGE:?POSTGRES_DEPLOY_IMAGE is required}
EOF

if ! recreate_postgresql "${CANDIDATE_IMAGE}" "${CANDIDATE_COMPOSE_FILE}"; then
  restore_previous "PostgreSQL candidate deployment command failed"
fi
if [[ "$(postgres_volume)" != "${POSTGRES_VOLUME}" ]]; then
  restore_previous "PostgreSQL candidate did not preserve the original named volume"
fi
if ! verify_runtime; then
  restore_previous "PostgreSQL candidate runtime verification failed"
fi
if [[ "$(container_image feelm-postgres)" != "${CANDIDATE_IMAGE}" ]]; then
  restore_previous "Running PostgreSQL image does not match the candidate image"
fi

echo "PostgreSQL deployment completed: ${CANDIDATE_REVISION}; backup: ${BACKUP_DIR}"
