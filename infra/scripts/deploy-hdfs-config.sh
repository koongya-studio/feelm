#!/usr/bin/env bash

set -Eeuo pipefail

NODE="${1:?Usage: deploy-hdfs-config.sh <service|distributed> <revision>|--initialize-state <node> <revision>}"
if [[ "${NODE}" == "--initialize-state" ]]; then
  INITIALIZE=true
  NODE="${2:?node is required}"
  REVISION="${3:?revision is required}"
else
  INITIALIZE=false
  REVISION="${2:?revision is required}"
fi
if [[ "${NODE}" != "service" && "${NODE}" != "distributed" ]]; then
  echo "HDFS deployment node must be service or distributed" >&2
  exit 2
fi
if [[ ! "${REVISION}" =~ ^[0-9a-f]{40}$ ]]; then
  echo "HDFS deployment revision must be a full lowercase commit SHA" >&2
  exit 2
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
RELEASE_ROOT="${RUNTIME_CONFIG_RELEASE_ROOT:-/var/lib/feelm-deploy/runtime-config}"
ENV_FILE="${HDFS_COMPOSE_ENV_FILE:?HDFS_COMPOSE_ENV_FILE is required}"
release_tool=(python3 -I -B "${SCRIPT_DIR}/runtime-config-release.py" --root "${RELEASE_ROOT}")

candidate="$("${release_tool[@]}" install --revision "${REVISION}")"
if [[ "${INITIALIZE}" == true ]]; then
  "${release_tool[@]}" write --component hdfs --node "${NODE}" --revision "${REVISION}"
  echo "HDFS deployment state initialized: ${NODE} ${REVISION}"
  exit 0
fi
previous_revision="$("${release_tool[@]}" read --component hdfs --node "${NODE}")"
previous="${RELEASE_ROOT}/releases/${previous_revision}"

python3 -I -B "${SCRIPT_DIR}/deploy-distributed-runtime.py" \
  --node "${NODE}" \
  --components hdfs \
  --env-file "${ENV_FILE}" \
  --source-root "${candidate}" \
  --rollback-root "${previous}" \
  --expected-commit "${REVISION}" \
  --expected-rollback-commit "${previous_revision}" \
  --confirm-deploy

"${release_tool[@]}" write --component hdfs --node "${NODE}" --revision "${REVISION}"
echo "HDFS configuration deployment completed: ${NODE} ${REVISION}"
