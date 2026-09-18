#!/usr/bin/env bash

set -Eeuo pipefail

if [[ "${1:-}" == "--initialize-state" ]]; then
  INITIALIZE=true
  REVISION="${2:?revision is required}"
else
  INITIALIZE=false
  REVISION="${1:?Usage: deploy-kafka-config.sh <revision>|--initialize-state <revision>}"
fi
if [[ ! "${REVISION}" =~ ^[0-9a-f]{40}$ ]]; then
  echo "Kafka deployment revision must be a full lowercase commit SHA" >&2
  exit 2
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RELEASE_ROOT="${RUNTIME_CONFIG_RELEASE_ROOT:-/var/lib/feelm-deploy/runtime-config}"
ENV_FILE="$(mktemp "${TMPDIR:-/tmp}/feelm-kafka-compose.XXXXXX")"
chmod 600 "${ENV_FILE}"
trap 'rm -f "${ENV_FILE}"' EXIT
release_tool=(python3 -I -B "${SCRIPT_DIR}/runtime-config-release.py" --root "${RELEASE_ROOT}")

candidate="$("${release_tool[@]}" install --revision "${REVISION}")"
if [[ "${INITIALIZE}" == true ]]; then
  "${release_tool[@]}" write --component kafka --node distributed --revision "${REVISION}"
  echo "Kafka deployment state initialized: distributed ${REVISION}"
  exit 0
fi

: "${KAFKA_CLUSTER_ID:?KAFKA_CLUSTER_ID is required}"
running_cluster_id="$(
  docker inspect feelm-kafka --format '{{range .Config.Env}}{{println .}}{{end}}' 2>/dev/null \
    | sed -n 's/^CLUSTER_ID=//p'
)"
if [[ -z "${running_cluster_id}" || "${running_cluster_id}" != "${KAFKA_CLUSTER_ID}" ]]; then
  echo "Running Kafka Cluster ID does not match the approved variable; deployment refused" >&2
  exit 1
fi

previous_revision="$("${release_tool[@]}" read --component kafka --node distributed)"
previous="${RELEASE_ROOT}/releases/${previous_revision}"

python3 -I -B "${SCRIPT_DIR}/deploy-distributed-runtime.py" \
  --node distributed \
  --components kafka \
  --env-file "${ENV_FILE}" \
  --source-root "${candidate}" \
  --rollback-root "${previous}" \
  --expected-commit "${REVISION}" \
  --expected-rollback-commit "${previous_revision}" \
  --confirm-deploy

"${release_tool[@]}" write --component kafka --node distributed --revision "${REVISION}"
echo "Kafka configuration deployment completed: distributed ${REVISION}"
