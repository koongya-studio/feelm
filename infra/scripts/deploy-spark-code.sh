#!/usr/bin/env bash

set -Eeuo pipefail

ACTION="${1:?Usage: deploy-spark-code.sh <preflight|initialize|redeploy|apply|settle|prepare|resolve> ...}"
NODE="${2:?node is required}"
if [[ "${NODE}" != "service" && "${NODE}" != "distributed" ]]; then
  echo "Spark deployment node must be service or distributed" >&2
  exit 2
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RELEASE_ROOT="${RUNTIME_CONFIG_RELEASE_ROOT:-/var/lib/feelm-deploy/runtime-config}"
STATE_DIR="${RELEASE_ROOT}/state"
ENV_FILE="$(mktemp "${TMPDIR:-/tmp}/feelm-spark-compose.XXXXXX")"
chmod 600 "${ENV_FILE}"
trap 'rm -f "${ENV_FILE}"' EXIT
release_tool=(python3 -I -B "${SCRIPT_DIR}/runtime-config-release.py" --root "${RELEASE_ROOT}")
state_tool=(python3 -I -B "${SCRIPT_DIR}/spark-code-state.py" --root "${RELEASE_ROOT}" --node "${NODE}")
deployer=(python3 -I -B "${SCRIPT_DIR}/deploy-distributed-runtime.py")
pending="${STATE_DIR}/spark-${NODE}-pending.json"

validate_revision() {
  [[ "$1" =~ ^[0-9a-f]{40}$ ]] || {
    echo "Spark revision must be a full lowercase commit SHA" >&2
    exit 2
  }
}

validate_image() {
  [[ "$1" =~ ^[^[:space:]@]+@sha256:[0-9a-f]{64}$ ]] || {
    echo "Spark image must be an immutable repository@sha256 digest" >&2
    exit 2
  }
}

state_field() {
  "${state_tool[@]}" field --scope "$1" --field "$2"
}

state_command() {
  local command="$1" revision="$2" image="$3" source_revision="$4"
  shift 4
  "${state_tool[@]}" "${command}" \
    --revision "${revision}" --image "${image}" \
    --source-revision "${source_revision}" "$@"
}

containers_for_node() {
  if [[ "${NODE}" == "distributed" ]]; then
    printf '%s\n' feelm-spark-master feelm-spark-worker-2
  else
    printf '%s\n' feelm-spark-worker-1
  fi
}

running_image() {
  local expected="" current
  while IFS= read -r container; do
    current="$(docker inspect "${container}" --format '{{.Config.Image}}')"
    [[ -n "${current}" ]] || { echo "Running Spark image is missing" >&2; exit 1; }
    if [[ -n "${expected}" && "${current}" != "${expected}" ]]; then
      echo "Running Spark containers do not use one consistent image" >&2
      exit 1
    fi
    expected="${current}"
  done < <(containers_for_node)
  printf '%s\n' "${expected}"
}

verify_running() {
  local expected_image="$1" expected_revision="$2" container image revision status
  validate_image "${expected_image}"
  validate_revision "${expected_revision}"
  while IFS= read -r container; do
    status="$(docker inspect "${container}" --format '{{.State.Status}}')"
    image="$(docker inspect "${container}" --format '{{.Config.Image}}')"
    revision="$(docker exec "${container}" cat /opt/feelm/pipeline/spark/.source-revision)"
    [[ "${status}" == "running" ]] || { echo "Spark container is not running: ${container}" >&2; exit 1; }
    [[ "${image}" == "${expected_image}" ]] || { echo "Spark image mismatch: ${container}" >&2; exit 1; }
    [[ "${revision}" == "${expected_revision}" ]] || { echo "Spark revision mismatch: ${container}" >&2; exit 1; }
  done < <(containers_for_node)
}

deploy_runtime() {
  local source_root="$1" rollback_root="$2" revision="$3" rollback_revision="$4"
  local image="$5" source_revision="$6" rollback_source_revision="$7" mode="${8:-normal}"
  local rollback_image="${9:-}"
  local extra=(--spark-revision "${source_revision}" --rollback-spark-revision "${rollback_source_revision}")
  if [[ "${mode}" == "recovery" ]]; then
    extra+=(--spark-recovery-mode --rollback-spark-image "${rollback_image}")
  fi
  "${deployer[@]}" --node "${NODE}" --components spark --env-file "${ENV_FILE}" \
    --source-root "${source_root}" --rollback-root "${rollback_root}" \
    --expected-commit "${revision}" --expected-rollback-commit "${rollback_revision}" \
    --spark-image "${image}" "${extra[@]}" --confirm-deploy
}

restore_pending() {
  local previous_revision previous_image previous_source_revision
  local candidate_revision candidate_image previous candidate
  previous_revision="$(state_field previous revision)"
  previous_image="$(state_field previous image)"
  previous_source_revision="$(state_field previous source_revision)"
  candidate_revision="$(state_field candidate revision)"
  candidate_image="$(state_field candidate image)"
  previous="${RELEASE_ROOT}/releases/${previous_revision}"
  candidate="${RELEASE_ROOT}/releases/${candidate_revision}"
  deploy_runtime "${previous}" "${candidate}" "${previous_revision}" \
    "${candidate_revision}" "${previous_image}" "${previous_source_revision}" \
    "${candidate_revision}" recovery "${candidate_image}"
  state_command restore-state "${candidate_revision}" "${candidate_image}" "${candidate_revision}"
}

ensure_previous_state() {
  local previous_revision previous_image current_revision current_image
  previous_revision="$(state_field previous source_revision)"
  previous_image="$(state_field previous image)"
  current_revision="$(state_field manifest source_revision)"
  current_image="$(state_field manifest image)"
  if [[ "${current_revision}" == "${previous_revision}" \
        && "${current_image}" == "${previous_image}" ]] \
      && verify_running "${previous_image}" "${previous_revision}"; then
    return 0
  fi
  restore_pending
}

case "${ACTION}" in
  redeploy)
    [[ ! -f "${pending}" ]] || {
      echo "Pending Spark transaction must be resolved before redeploy" >&2
      exit 1
    }
    revision="$(state_field manifest revision)"
    image="$(state_field manifest image)"
    source_revision="$(state_field manifest source_revision)"
    release="${RELEASE_ROOT}/releases/${revision}"
    [[ -d "${release}" ]] || { echo "Spark last-known-good release is missing" >&2; exit 1; }
    verify_running "${image}" "${source_revision}"
    deploy_runtime "${release}" "${release}" "${revision}" "${revision}" \
      "${image}" "${source_revision}" "${source_revision}"
    verify_running "${image}" "${source_revision}"
    echo "Spark current main environment reapplied: ${NODE}"
    ;;
  preflight)
    REVISION="${3:?revision is required}"
    IMAGE="${4:?image is required}"
    validate_revision "${REVISION}"
    validate_image "${IMAGE}"
    if [[ -f "${pending}" ]]; then
      echo "Interrupted Spark transaction detected; restoring last-known-good first"
      interrupted_revision="$(state_field candidate revision)"
      interrupted_image="$(state_field candidate image)"
      if ! ensure_previous_state || ! state_command resolve \
          "${interrupted_revision}" "${interrupted_image}" \
          "${interrupted_revision}" --outcome failed >/dev/null; then
        echo "Interrupted Spark transaction recovery failed" >&2
        exit 3
      fi
    fi
    "${deployer[@]}" --node "${NODE}" --components spark \
      --spark-image "${IMAGE}" --spark-revision "${REVISION}" --preflight-only
    ;;
  initialize)
    REVISION="${3:?revision is required}"
    APPROVED_IMAGE="${4:?immutable code image digest is required}"
    validate_revision "${REVISION}"
    validate_image "${APPROVED_IMAGE}"
    candidate="$("${release_tool[@]}" install --revision "${REVISION}")"
    IMAGE="$(running_image)"
    docker pull "${APPROVED_IMAGE}" >/dev/null
    running_id="$(docker image inspect "${IMAGE}" --format '{{.Id}}')"
    approved_id="$(docker image inspect "${APPROVED_IMAGE}" --format '{{.Id}}')"
    approved_revision="$(docker image inspect "${APPROVED_IMAGE}" --format '{{index .Config.Labels "org.opencontainers.image.revision"}}')"
    [[ "${running_id}" == "${approved_id}" ]] || {
      echo "Approved Spark image digest does not match the running image" >&2
      exit 1
    }
    [[ "${approved_revision}" == "${REVISION}" ]] || {
      echo "Approved Spark image revision does not match the release revision" >&2
      exit 1
    }
    verify_running "${APPROVED_IMAGE}" "${REVISION}"
    state_command initialize "${REVISION}" "${APPROVED_IMAGE}" "${REVISION}"
    echo "Spark deployment state initialized: ${NODE} ${candidate}"
    ;;
  apply)
    REVISION="${3:?revision is required}"
    IMAGE="${4:?image is required}"
    validate_revision "${REVISION}"
    validate_image "${IMAGE}"
    candidate="$("${release_tool[@]}" install --revision "${REVISION}")"
    previous_revision="$(state_field manifest revision)"
    previous_image="$(state_field manifest image)"
    previous_source_revision="$(state_field manifest source_revision)"
    previous="${RELEASE_ROOT}/releases/${previous_revision}"
    state_command begin "${REVISION}" "${IMAGE}" "${REVISION}"
    deploy_runtime "${candidate}" "${previous}" "${REVISION}" "${previous_revision}" \
      "${IMAGE}" "${REVISION}" "${previous_source_revision}"
    ;;
  settle)
    REVISION="${3:?revision is required}"
    IMAGE="${4:?image is required}"
    OUTCOME="${5:?success or failed is required}"
    validate_revision "${REVISION}"
    validate_image "${IMAGE}"
    [[ "${OUTCOME}" == "success" || "${OUTCOME}" == "failed" ]] || exit 2
    if [[ "${OUTCOME}" == "success" ]]; then
      verify_running "${IMAGE}" "${REVISION}"
      state_command promote "${REVISION}" "${IMAGE}" "${REVISION}"
      echo "Spark transaction prepared for commit: ${NODE}"
    elif [[ -f "${pending}" ]]; then
      ensure_previous_state
      echo "Spark transaction prepared for rollback: ${NODE}"
    fi
    ;;
  prepare)
    REVISION="${3:?revision is required}"
    IMAGE="${4:?image is required}"
    OUTCOME="${5:?success or failed is required}"
    validate_revision "${REVISION}"
    validate_image "${IMAGE}"
    [[ "${OUTCOME}" == "success" || "${OUTCOME}" == "failed" ]] || exit 2
    if [[ "${OUTCOME}" == "success" ]]; then
      verify_running "${IMAGE}" "${REVISION}"
    elif [[ -f "${pending}" ]]; then
      ensure_previous_state
    else
      current_image="$(state_field manifest image)"
      current_revision="$(state_field manifest source_revision)"
      verify_running "${current_image}" "${current_revision}"
    fi
    echo "Spark transaction resolution prepared: ${NODE} ${OUTCOME}"
    ;;
  resolve)
    REVISION="${3:?revision is required}"
    IMAGE="${4:?image is required}"
    OUTCOME="${5:?success or failed is required}"
    validate_revision "${REVISION}"
    validate_image "${IMAGE}"
    [[ "${OUTCOME}" == "success" || "${OUTCOME}" == "failed" ]] || exit 2
    if [[ "${OUTCOME}" == "success" ]]; then
      verify_running "${IMAGE}" "${REVISION}"
    elif [[ -f "${pending}" ]]; then
      ensure_previous_state
    else
      current_image="$(state_field manifest image)"
      current_revision="$(state_field manifest source_revision)"
      verify_running "${current_image}" "${current_revision}"
    fi
    state_command resolve "${REVISION}" "${IMAGE}" "${REVISION}" --outcome "${OUTCOME}"
    ;;
  *) echo "Unsupported Spark deployment action: ${ACTION}" >&2; exit 2 ;;
esac
