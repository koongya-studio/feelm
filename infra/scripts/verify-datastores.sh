#!/usr/bin/env bash

set -Eeuo pipefail

require_healthy() {
  local container="$1"
  local status
  status="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "${container}")"
  if [[ "${status}" != "healthy" ]]; then
    echo "${container} is not healthy: ${status}" >&2
    return 1
  fi
}

require_loopback_port() {
  local container="$1"
  local port="$2"
  local expected="127.0.0.1:${port}"
  local bindings
  bindings="$(docker port "${container}" "${port}")"
  if [[ "${bindings}" != "${expected}" ]]; then
    echo "${container} port ${port} is not loopback-only: ${bindings:-none}" >&2
    return 1
  fi
}

require_healthy feelm-postgres
require_healthy feelm-redis
require_loopback_port feelm-postgres 5432
require_loopback_port feelm-redis 6379

postgres_result="$(docker exec feelm-postgres sh -c \
  'psql -X -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc "SELECT 1;"')"
[[ "${postgres_result}" == "1" ]] || {
  echo "PostgreSQL SELECT 1 verification failed" >&2
  exit 1
}

docker exec feelm-redis sh -c \
  'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli --no-auth-warning ping' \
  | grep -qx PONG
docker exec feelm-redis redis-cli --no-auth-warning ping \
  | grep -q '^NOAUTH '

docker exec feelm-redis sh -c \
  'test -n "$REDIS_READONLY_PASSWORD"' || {
  echo "REDIS_READONLY_PASSWORD is not configured" >&2
  exit 1
}
docker exec feelm-redis sh -c \
  'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli --no-auth-warning ACL USERS' \
  | grep -qx feelm_readonly
docker exec feelm-redis sh -c \
  'redis-cli --no-auth-warning --user feelm_readonly --pass "$REDIS_READONLY_PASSWORD" GET feelm:user:0:topn' \
  >/dev/null
docker exec feelm-redis sh -c \
  'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli --no-auth-warning ACL DRYRUN feelm_readonly SET feelm:user:0:topn value' \
  | grep -q 'no permissions'
docker exec feelm-redis sh -c \
  'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli --no-auth-warning ACL DRYRUN feelm_readonly DEL feelm:user:0:topn' \
  | grep -q 'no permissions'

echo "Datastore runtime verification passed"
