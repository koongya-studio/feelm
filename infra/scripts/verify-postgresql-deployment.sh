#!/usr/bin/env bash

set -Eeuo pipefail

postgres_status="$(docker inspect --format \
  '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' feelm-postgres)"
if [[ "${postgres_status}" != "healthy" ]]; then
  echo "feelm-postgres is not healthy: ${postgres_status}" >&2
  exit 1
fi

postgres_result="$(docker exec feelm-postgres sh -c \
  'psql -X -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc "SELECT 1;"')"
postgres_result="${postgres_result//[[:space:]]/}"
if [[ "${postgres_result}" != "1" ]]; then
  echo "PostgreSQL SELECT 1 verification failed" >&2
  exit 1
fi

backend_status="$(docker inspect --format \
  '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' feelm-backend)"
if [[ "${backend_status}" != "healthy" ]]; then
  echo "feelm-backend is not healthy after PostgreSQL deployment: ${backend_status}" >&2
  exit 1
fi

backend_health="$(docker exec feelm-backend \
  curl --fail --silent http://localhost:8080/actuator/health)"
if [[ "${backend_health}" != *'"status":"UP"'* ]]; then
  echo "Backend actuator health did not confirm the PostgreSQL-backed application is UP" >&2
  exit 1
fi

echo "PostgreSQL and backend connection verification passed"
