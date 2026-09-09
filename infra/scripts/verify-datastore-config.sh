#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
DOCKER_PROJECT_ROOT="${PROJECT_ROOT}"
REDIS_IMAGE="redis:8.2.9-alpine@sha256:30abb90e62f14b737010746def3ba99cc79fe19dcdb3d37b41f21fc62e7da19d"
CONTAINER_NAME="feelm-redis-config-check-${CI_JOB_ID:-$$}"

docker_cli() {
  if [[ -n "${MSYSTEM:-}" ]]; then
    MSYS2_ARG_CONV_EXCL='*' docker "$@"
  else
    docker "$@"
  fi
}

if [[ -n "${MSYSTEM:-}" ]]; then
  DOCKER_PROJECT_ROOT="$(cygpath -w "${PROJECT_ROOT}")"
fi

cleanup() {
  docker_cli rm -f "${CONTAINER_NAME}" >/dev/null 2>&1 || true
}
trap cleanup EXIT

# Configuration checks use fixed non-secret values even on the protected deploy runner.
export SERVICE_NODE_IP="127.0.0.1"
export DISTRIBUTED_NODE_IP="127.0.0.2"
export POSTGRES_DB="feelm_ci"
export POSTGRES_USER="feelm_ci"
export POSTGRES_PASSWORD="ci-postgres-placeholder"
export BACKEND_IMAGE="local/feelm-backend:config-check"
export DB_URL="jdbc:postgresql://postgres:5432/${POSTGRES_DB}"
export DB_USERNAME="${POSTGRES_USER}"
export DB_PASSWORD="${POSTGRES_PASSWORD}"
export REDIS_PASSWORD="ci-redis-application-placeholder"
export REDIS_READONLY_PASSWORD="ci-redis-readonly-placeholder"
export JWT_SECRET="ci-jwt-placeholder-at-least-32-bytes"
export CORS_ALLOWED_ORIGINS="http://127.0.0.1"
export FRONTEND_BASE_URL="http://127.0.0.1"
export MAIL_USERNAME="ci@example.invalid"
export MAIL_PASSWORD="ci-mail-placeholder"

bash -n "${PROJECT_ROOT}/infra/scripts/verify-datastore-config.sh"
bash -n "${PROJECT_ROOT}/infra/scripts/verify-datastores.sh"
sh -n "${PROJECT_ROOT}/infra/redis/start.sh"
docker_cli compose -f "${DOCKER_PROJECT_ROOT}/infra/compose/service.yml" config --quiet

docker_cli run -d \
  --name "${CONTAINER_NAME}" \
  --network none \
  -e REDIS_PASSWORD="${REDIS_PASSWORD}" \
  -e REDIS_READONLY_PASSWORD="${REDIS_READONLY_PASSWORD}" \
  -v "${DOCKER_PROJECT_ROOT}/infra/redis/start.sh:/usr/local/etc/redis/start.sh:ro" \
  -v "${DOCKER_PROJECT_ROOT}/infra/redis/readonly.acl:/usr/local/etc/redis/readonly.acl:ro" \
  "${REDIS_IMAGE}" \
  sh /usr/local/etc/redis/start.sh >/dev/null

redis_ready=false
for _ in {1..20}; do
  if docker_cli exec "${CONTAINER_NAME}" sh -c \
    'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli --no-auth-warning ping' 2>/dev/null \
    | grep -qx PONG; then
    redis_ready=true
    break
  fi
  sleep 1
done

if [[ "${redis_ready}" != "true" ]]; then
  echo "Temporary Redis did not become ready" >&2
  docker_cli inspect --format 'status={{.State.Status}} exit={{.State.ExitCode}}' \
    "${CONTAINER_NAME}" >&2 || true
  docker_cli logs "${CONTAINER_NAME}" >&2 || true
  exit 1
fi

docker_cli exec "${CONTAINER_NAME}" sh -c \
  'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli --no-auth-warning ping' \
  | grep -qx PONG
docker_cli exec "${CONTAINER_NAME}" redis-cli --no-auth-warning ping \
  | grep -q '^NOAUTH '
docker_cli exec "${CONTAINER_NAME}" sh -c \
  'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli --no-auth-warning ACL DRYRUN feelm_readonly GET feelm:user:1:topn' \
  | grep -qx OK
docker_cli exec "${CONTAINER_NAME}" sh -c \
  'redis-cli --no-auth-warning --user feelm_readonly --pass "$REDIS_READONLY_PASSWORD" GET feelm:user:1:topn' \
  >/dev/null
docker_cli exec "${CONTAINER_NAME}" sh -c \
  'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli --no-auth-warning ACL DRYRUN feelm_readonly SET feelm:user:1:topn value' \
  | grep -q 'no permissions'

echo "Datastore configuration verification passed"
