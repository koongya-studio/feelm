#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

exec /usr/bin/python3 -I -B "${SCRIPT_DIR}/deploy-distributed-runtime.py" \
  --node service "$@"
