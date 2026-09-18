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

require_expected_port() {
  local container="$1"
  local port="$2"
  local bind_address="$3"
  local expected="${bind_address}:${port}"
  local bindings
  bindings="$(docker port "${container}" "${port}")"
  if [[ "${bindings}" != "${expected}" ]]; then
    echo "${container} port ${port} does not match ${expected}: ${bindings:-none}" >&2
    return 1
  fi
}

require_private_ipv4() {
  local name="$1"
  local address="$2"
  local first_octet
  local second_octet
  local third_octet
  local fourth_octet
  local extra
  local octet

  IFS=. read -r first_octet second_octet third_octet fourth_octet extra <<<"${address}"
  for octet in "${first_octet}" "${second_octet}" "${third_octet}" "${fourth_octet}"; do
    if [[ ! "${octet}" =~ ^(0|[1-9][0-9]{0,2})$ ]] || ((10#${octet} > 255)); then
      echo "${name} must be an RFC1918 private IPv4 address: ${address:-missing}" >&2
      return 1
    fi
  done
  if [[ -n "${extra}" ]]; then
    echo "${name} must be an RFC1918 private IPv4 address: ${address:-missing}" >&2
    return 1
  fi

  if ((10#${first_octet} == 10)) \
    || ((10#${first_octet} == 192 && 10#${second_octet} == 168)) \
    || ((10#${first_octet} == 172 \
      && 10#${second_octet} >= 16 && 10#${second_octet} <= 31)); then
    return 0
  fi

  echo "${name} must be an RFC1918 private IPv4 address: ${address:-missing}" >&2
  return 1
}

require_remote_datastore_firewall() {
  local iptables_command="${IPTABLES_BIN:-iptables}"
  local iptables_bin
  local expected_rule
  local first_rule
  local rules

  if ! iptables_bin="$(command -v "${iptables_command}")"; then
    echo "iptables is required to verify remote datastore firewall rules" >&2
    return 1
  fi

  expected_rule="-A DOCKER-USER ! -s ${DISTRIBUTED_NODE_IP}/32 -p tcp -m multiport --dports 5432,6379 -j DROP"
  if ! rules="$("${iptables_bin}" -S DOCKER-USER 2>/dev/null)"; then
    if ! command -v sudo >/dev/null 2>&1 \
      || ! rules="$(sudo -n "${iptables_bin}" -S DOCKER-USER 2>/dev/null)"; then
      echo "Cannot read DOCKER-USER firewall rules; grant the deploy runner read-only iptables access" >&2
      return 1
    fi
  fi
  if ! first_rule="$(grep -m1 '^-A DOCKER-USER ' <<<"${rules}")"; then
    echo "Cannot read the first DOCKER-USER firewall rule" >&2
    return 1
  fi

  if [[ "${first_rule}" != "${expected_rule}" ]]; then
    echo "Remote datastore firewall rule is missing or not first in DOCKER-USER" >&2
    echo "Expected: ${expected_rule}" >&2
    echo "Actual: ${first_rule:-none}" >&2
    return 1
  fi
}

postgres_bind_address="${POSTGRES_BIND_ADDRESS:-127.0.0.1}"
redis_bind_address="${REDIS_BIND_ADDRESS:-127.0.0.1}"

if [[ "${postgres_bind_address}" == "127.0.0.1" \
  && "${redis_bind_address}" == "127.0.0.1" ]]; then
  :
elif [[ "${postgres_bind_address}" == "127.0.0.1" \
  || "${redis_bind_address}" == "127.0.0.1" ]]; then
  echo "PostgreSQL and Redis must both use loopback or both use the service-node address" >&2
  exit 1
else
  : "${SERVICE_NODE_IP:?SERVICE_NODE_IP is required for remote datastore binding}"
  : "${DISTRIBUTED_NODE_IP:?DISTRIBUTED_NODE_IP is required for remote datastore binding}"
  require_private_ipv4 SERVICE_NODE_IP "${SERVICE_NODE_IP}"
  require_private_ipv4 DISTRIBUTED_NODE_IP "${DISTRIBUTED_NODE_IP}"
  if [[ "${SERVICE_NODE_IP}" == "${DISTRIBUTED_NODE_IP}" ]]; then
    echo "SERVICE_NODE_IP and DISTRIBUTED_NODE_IP must be different" >&2
    exit 1
  fi
  if [[ "${postgres_bind_address}" != "${SERVICE_NODE_IP}" \
    || "${redis_bind_address}" != "${SERVICE_NODE_IP}" ]]; then
    echo "Remote datastore bindings must both equal SERVICE_NODE_IP" >&2
    exit 1
  fi
  require_remote_datastore_firewall
fi

require_healthy feelm-postgres
require_healthy feelm-redis
require_expected_port feelm-postgres 5432 "${postgres_bind_address}"
require_expected_port feelm-redis 6379 "${redis_bind_address}"

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

docker exec feelm-redis sh -c \
  'test -n "$REDIS_LOCAL_BACKEND_PASSWORD"' || {
  echo "REDIS_LOCAL_BACKEND_PASSWORD is not configured" >&2
  exit 1
}
docker exec feelm-redis sh -c \
  'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli --no-auth-warning ACL USERS' \
  | grep -qx feelm_local_backend
docker exec feelm-redis sh -c \
  'redis-cli --no-auth-warning --user feelm_local_backend --pass "$REDIS_LOCAL_BACKEND_PASSWORD" GET feelm:user:0:candidates' \
  >/dev/null
docker exec feelm-redis sh -c \
  'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli --no-auth-warning ACL DRYRUN feelm_local_backend SET auth:runtime-check value' \
  | grep -qx OK
docker exec feelm-redis sh -c \
  'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli --no-auth-warning ACL DRYRUN feelm_local_backend SET feelm:user:0:candidates value' \
  | grep -q 'no permissions'
docker exec feelm-redis sh -c \
  'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli --no-auth-warning ACL DRYRUN feelm_local_backend FLUSHALL' \
  | grep -q 'no permissions'

echo "Datastore runtime verification passed"
