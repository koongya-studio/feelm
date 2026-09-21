#!/usr/bin/env bash

set -Eeuo pipefail

COMPONENT="${1:?Usage: redeploy-current-main.sh <backend|frontend|consumer|redis|monitoring>}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

case "${COMPONENT}" in
  backend|frontend|consumer|redis|monitoring) ;;
  *) echo "Unsupported redeploy component: ${COMPONENT}" >&2; exit 2 ;;
esac

container_image_digest() {
  local container="$1" status health image digest
  status="$(docker inspect "${container}" --format '{{.State.Status}}')"
  [[ "${status}" == "running" ]] || {
    echo "Last successful container is not running: ${container}" >&2
    return 1
  }
  health="$(docker inspect "${container}" --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}')"
  [[ "${health}" == "none" || "${health}" == "healthy" ]] || {
    echo "Last successful container is not healthy: ${container}" >&2
    return 1
  }
  image="$(docker inspect "${container}" --format '{{.Config.Image}}')"
  [[ -n "${image}" ]] || { echo "Running image is missing: ${container}" >&2; return 1; }
  if [[ "${image}" =~ ^[^[:space:]@]+@sha256:[0-9a-f]{64}$ ]]; then
    digest="${image}"
  else
    digest="$(docker image inspect "${image}" --format '{{index .RepoDigests 0}}')"
  fi
  [[ "${digest}" =~ ^[^[:space:]@]+@sha256:[0-9a-f]{64}$ ]] || {
    echo "Running image has no immutable repository digest: ${container}" >&2
    return 1
  }
  docker image inspect "${digest}" >/dev/null
  printf '%s\n' "${digest}"
}

mounted_source() {
  docker inspect "$1" --format "{{range .Mounts}}{{if eq .Destination \"$2\"}}{{.Source}}{{end}}{{end}}"
}

write_override() {
  local file="$1"; shift
  printf 'services:\n' >"${file}"
  while (($#)); do
    printf '  %s:\n    image: %s\n' "$1" "$2" >>"${file}"
    shift 2
  done
}

case "${COMPONENT}" in
  backend)
    exec "${SCRIPT_DIR}/deploy-backend.sh" "$(container_image_digest feelm-backend)"
    ;;
  frontend)
    export BACKEND_IMAGE="$(container_image_digest feelm-backend)"
    exec "${SCRIPT_DIR}/deploy-frontend.sh" "$(container_image_digest feelm-frontend)"
    ;;
  consumer)
    export DEPLOY_PIPELINE_SOURCE="${CI_PIPELINE_SOURCE:-web}"
    exec "${SCRIPT_DIR}/deploy-consumer.sh" "$(container_image_digest feelm-foldin-consumer)"
    ;;
  redis)
    redis_image="$(container_image_digest feelm-redis)"
    export BACKEND_IMAGE="$(container_image_digest feelm-backend)"
    export REDIS_CONFIG_DIR="$(mounted_source feelm-redis /usr/local/etc/redis/start.sh)"
    REDIS_CONFIG_DIR="${REDIS_CONFIG_DIR%/start.sh}"
    export REDIS_CONFIG_DIR
    [[ -n "${REDIS_CONFIG_DIR}" && -f "${REDIS_CONFIG_DIR}/start.sh" ]] || {
      echo "Running Redis configuration mount could not be resolved" >&2
      exit 1
    }
    override="$(mktemp "${TMPDIR:-/tmp}/feelm-redis-redeploy.XXXXXX.yml")"
    trap 'rm -f "${override}"' EXIT
    write_override "${override}" redis "${redis_image}"
    compose=(docker compose)
    [[ -z "${COMPOSE_ENV_FILE:-}" ]] || compose+=(--env-file "${COMPOSE_ENV_FILE}")
    compose+=(-f "${PROJECT_ROOT}/infra/compose/service.yml" -f "${override}")
    "${compose[@]}" config --quiet
    "${compose[@]}" up -d --no-deps --force-recreate redis
    "${SCRIPT_DIR}/verify-datastores.sh"
    ;;
  monitoring)
    override="$(mktemp "${TMPDIR:-/tmp}/feelm-monitoring-redeploy.XXXXXX.yml")"
    trap 'rm -f "${override}"' EXIT
    write_override "${override}" \
      loki "$(container_image_digest feelm-loki)" \
      alloy "$(container_image_digest feelm-alloy)" \
      prometheus "$(container_image_digest feelm-prometheus)" \
      grafana "$(container_image_digest feelm-grafana)" \
      mattermost-alert-relay "$(container_image_digest feelm-mattermost-alert-relay)" \
      node-exporter "$(container_image_digest feelm-node-exporter)" \
      postgres-exporter "$(container_image_digest feelm-postgres-exporter)" \
      redis-exporter "$(container_image_digest feelm-redis-exporter)"
    export MONITORING_IMAGE_OVERRIDE_FILE="${override}"
    "${SCRIPT_DIR}/deploy-monitoring.sh"
    ;;
esac
