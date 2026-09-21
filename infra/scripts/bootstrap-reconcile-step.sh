#!/usr/bin/env bash

set -Eeuo pipefail

STEP="${1:?Usage: bootstrap-reconcile-step.sh <step> <service|distributed>}"
NODE="${2:?Usage: bootstrap-reconcile-step.sh <step> <service|distributed>}"
MODE="${INFRA_RECONCILE_MODE:-reconcile}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

if [[ "${NODE}" != service && "${NODE}" != distributed ]]; then
  echo "Node must be service or distributed" >&2
  exit 2
fi
if [[ "${MODE}" != bootstrap && "${MODE}" != reconcile ]]; then
  echo "INFRA_RECONCILE_MODE must be bootstrap or reconcile" >&2
  exit 2
fi
if [[ "${MODE}" == bootstrap \
  && "${INFRA_BOOTSTRAP_CONFIRM:-}" != INITIALIZE_EMPTY_STATE ]]; then
  echo "Bootstrap requires INFRA_BOOTSTRAP_CONFIRM=INITIALIZE_EMPTY_STATE" >&2
  exit 2
fi

compose=(docker compose --project-directory "${PROJECT_ROOT}/infra/compose")
if [[ -n "${COMPOSE_ENV_FILE:-}" ]]; then
  [[ -f "${COMPOSE_ENV_FILE}" ]] || {
    echo "COMPOSE_ENV_FILE does not exist" >&2
    exit 2
  }
  compose+=(--env-file "${COMPOSE_ENV_FILE}")
fi
if [[ "${NODE}" == service ]]; then
  compose+=(-f "${PROJECT_ROOT}/infra/compose/service.yml")
else
  compose+=(-f "${PROJECT_ROOT}/infra/compose/distributed.yml")
fi

container_exists() {
  docker inspect "$1" >/dev/null 2>&1
}

require_digest() {
  local name="$1"
  local value="${!name:-}"
  if [[ ! "${value}" =~ ^[^[:space:]@]+@sha256:[0-9a-f]{64}$ ]]; then
    echo "${name} must be an immutable image digest reference" >&2
    exit 2
  fi
}

require_revision() {
  local name="$1"
  local value="${!name:-}"
  if [[ ! "${value}" =~ ^[0-9a-f]{40}$ ]]; then
    echo "${name} must be a full lowercase commit SHA" >&2
    exit 2
  fi
}

wait_container() {
  local container="$1"
  local expected="$2"
  local attempts="${INFRA_HEALTH_ATTEMPTS:-36}"
  local interval="${INFRA_HEALTH_INTERVAL_SECONDS:-5}"
  local state="missing"
  local i
  for ((i = 1; i <= attempts; i++)); do
    state="$(docker inspect --format \
      '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' \
      "${container}" 2>/dev/null || true)"
    if [[ "${state}" == "${expected}" ]]; then
      return 0
    fi
    if [[ "${state}" == unhealthy || "${state}" == exited || "${state}" == dead ]]; then
      echo "${container} entered terminal state: ${state}" >&2
      return 1
    fi
    if ((i < attempts)); then
      sleep "${interval}"
    fi
  done
  echo "${container} did not reach ${expected}: ${state}" >&2
  return 1
}

verify_postgresql_core() {
  wait_container feelm-postgres healthy
  result="$(docker exec feelm-postgres sh -c \
    'psql -X -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc "SELECT 1;"')"
  result="${result//[[:space:]]/}"
  [[ "${result}" == 1 ]] || {
    echo "PostgreSQL SELECT 1 verification failed" >&2
    return 1
  }
}

preflight() {
  python3 -I -B "${SCRIPT_DIR}/bootstrap-reconcile.py" \
    --mode "${MODE}" --node "${NODE}" --json
}

decision() {
  local component="$1"
  python3 -c 'import json,sys; component=sys.argv[1]; report=json.load(sys.stdin); print(next(item["action"] for item in report["decisions"] if item["component"] == component))' \
    "${component}"
}

current_commit() {
  /usr/bin/python3 -I -B "${SCRIPT_DIR}/ci-deploy-current-commit.py" \
    --branch main --pipeline-source parent_pipeline
}

runtime_state_exists() {
  local component="$1"
  local node="$2"
  local root="${RUNTIME_CONFIG_RELEASE_ROOT:-/var/lib/feelm-deploy/runtime-config}"
  python3 -I -B "${SCRIPT_DIR}/runtime-config-release.py" \
    --root "${root}" read --component "${component}" --node "${node}" \
    >/dev/null 2>&1
}

spark_state_field() {
  local node="$1"
  local field="$2"
  local root="${RUNTIME_CONFIG_RELEASE_ROOT:-/var/lib/feelm-deploy/runtime-config}"
  python3 -I -B "${SCRIPT_DIR}/spark-code-state.py" \
    --root "${root}" --node "${node}" field \
    --scope manifest --field "${field}" 2>/dev/null
}

validate_spark_state() {
  local node="$1"
  local image source_revision
  if ! image="$(spark_state_field "${node}" image)" \
    || ! source_revision="$(spark_state_field "${node}" source_revision)"; then
    return 1
  fi
  [[ "${image}" == "${INFRA_SPARK_IMAGE}" ]] || {
    echo "Spark deployment state image differs on ${node}" >&2
    exit 1
  }
  [[ "${source_revision}" == "${INFRA_SPARK_REVISION}" ]] || {
    echo "Spark deployment state revision differs on ${node}" >&2
    exit 1
  }
}

current_commit

case "${STEP}" in
  preflight)
    if [[ "${NODE}" == service ]]; then
      require_digest INFRA_BACKEND_IMAGE
      require_digest INFRA_FRONTEND_IMAGE
      require_digest INFRA_SPARK_IMAGE
      require_revision INFRA_SPARK_REVISION
      if [[ "${MODE}" == reconcile ]]; then
        runtime_state_exists hdfs service || {
          echo "HDFS deployment state is missing in reconcile mode: service" >&2
          exit 1
        }
        validate_spark_state service || {
          echo "Spark deployment state is missing in reconcile mode: service" >&2
          exit 1
        }
      elif spark_state_field service image >/dev/null 2>&1; then
        validate_spark_state service
      fi
    else
      require_digest INFRA_CONSUMER_IMAGE
      require_digest INFRA_SPARK_IMAGE
      require_revision INFRA_SPARK_REVISION
      : "${CONSUMER_COMPOSE_ENV_FILE:?CONSUMER_COMPOSE_ENV_FILE is required}"
      [[ -f "${CONSUMER_COMPOSE_ENV_FILE}" ]] || {
        echo "CONSUMER_COMPOSE_ENV_FILE does not exist" >&2
        exit 2
      }
      if [[ "${MODE}" == reconcile ]]; then
        runtime_state_exists hdfs distributed || {
          echo "HDFS deployment state is missing in reconcile mode: distributed" >&2
          exit 1
        }
        runtime_state_exists kafka distributed || {
          echo "Kafka deployment state is missing in reconcile mode" >&2
          exit 1
        }
        [[ -f "${CONSUMER_DEPLOY_STATE_FILE:-/var/lib/feelm-deploy/consumer-last-known-good}" ]] || {
          echo "Consumer deployment state is missing in reconcile mode" >&2
          exit 1
        }
        validate_spark_state distributed || {
          echo "Spark deployment state is missing in reconcile mode: distributed" >&2
          exit 1
        }
      elif spark_state_field distributed image >/dev/null 2>&1; then
        validate_spark_state distributed
      fi
    fi
    preflight
    ;;
  postgresql)
    [[ "${NODE}" == service ]] || exit 2
    require_digest INFRA_BACKEND_IMAGE
    preflight >/dev/null
    if container_exists feelm-postgres; then
      current_postgres_image="$(docker inspect --format '{{.Config.Image}}' feelm-postgres)"
      candidate_postgres_image="$({
        BACKEND_IMAGE="${INFRA_BACKEND_IMAGE}" "${compose[@]}" config --format json
      } | python3 -c 'import json,sys; print(json.load(sys.stdin)["services"]["postgres"]["image"])')"
      if [[ "${current_postgres_image}" == "${candidate_postgres_image}" ]]; then
        verify_postgresql_core
      elif container_exists feelm-backend; then
        BACKEND_IMAGE="${INFRA_BACKEND_IMAGE}" \
          bash "${SCRIPT_DIR}/deploy-postgresql.sh" "${CI_COMMIT_SHA}"
      else
        echo "PostgreSQL image change requires a running Backend for verified rollback" >&2
        exit 1
      fi
    else
      BACKEND_IMAGE="${INFRA_BACKEND_IMAGE}" \
        "${compose[@]}" up -d --no-deps postgres
      verify_postgresql_core
    fi
    ;;
  redis)
    [[ "${NODE}" == service ]] || exit 2
    require_digest INFRA_BACKEND_IMAGE
    preflight >/dev/null
    if container_exists feelm-redis; then
      BACKEND_IMAGE="${INFRA_BACKEND_IMAGE}" \
        bash "${SCRIPT_DIR}/deploy-redis.sh" "${CI_COMMIT_SHA}"
    else
      BACKEND_IMAGE="${INFRA_BACKEND_IMAGE}" \
        "${compose[@]}" up -d --no-deps redis
      wait_container feelm-redis healthy
      bash "${SCRIPT_DIR}/verify-datastores.sh"
    fi
    ;;
  hdfs)
    report="$(preflight)"
    if runtime_state_exists hdfs "${NODE}"; then
      hdfs_state_initialized=true
    else
      hdfs_state_initialized=false
      if [[ "${MODE}" == reconcile ]]; then
        echo "HDFS deployment state is missing in reconcile mode: ${NODE}" >&2
        exit 1
      fi
    fi
    if [[ "${NODE}" == distributed ]]; then
      hdfs_action="$(decision hdfs <<<"${report}")"
      if [[ "${hdfs_action}" == initialize-once ]]; then
        "${compose[@]}" --profile init run --rm --no-deps hdfs-namenode-init
        post_init_report="$(preflight)"
        if [[ "$(decision hdfs <<<"${post_init_report}")" != reuse-without-format ]]; then
          echo "HDFS NameNode metadata was not created as expected" >&2
          exit 1
        fi
      fi
      if ! container_exists feelm-hdfs-namenode; then
        "${compose[@]}" up -d --no-deps hdfs-namenode
      fi
      wait_container feelm-hdfs-namenode healthy
      if ! container_exists feelm-hdfs-datanode-2; then
        "${compose[@]}" up -d --no-deps hdfs-datanode-2
      fi
      wait_container feelm-hdfs-datanode-2 healthy
      if [[ "${hdfs_state_initialized}" != true ]]; then
        bash "${SCRIPT_DIR}/deploy-hdfs-config.sh" \
          --initialize-state distributed "${CI_COMMIT_SHA}"
      fi
    else
      require_digest INFRA_BACKEND_IMAGE
      if ! container_exists feelm-hdfs-datanode-1; then
        BACKEND_IMAGE="${INFRA_BACKEND_IMAGE}" \
          "${compose[@]}" up -d --no-deps hdfs-datanode-1
      fi
      wait_container feelm-hdfs-datanode-1 healthy
      if [[ "${hdfs_state_initialized}" == true ]]; then
        BACKEND_IMAGE="${INFRA_BACKEND_IMAGE}" \
          bash "${SCRIPT_DIR}/deploy-hdfs-config.sh" service "${CI_COMMIT_SHA}"
      else
        bash "${SCRIPT_DIR}/deploy-hdfs-config.sh" \
          --initialize-state service "${CI_COMMIT_SHA}"
      fi
    fi
    ;;
  hdfs-finalize)
    [[ "${NODE}" == distributed ]] || exit 2
    preflight >/dev/null
    runtime_state_exists hdfs distributed || {
      echo "HDFS deployment state is missing: distributed" >&2
      exit 1
    }
    wait_container feelm-hdfs-namenode healthy
    wait_container feelm-hdfs-datanode-2 healthy
    bash "${SCRIPT_DIR}/deploy-hdfs-config.sh" distributed "${CI_COMMIT_SHA}"
    ;;
  kafka)
    [[ "${NODE}" == distributed ]] || exit 2
    preflight >/dev/null
    if runtime_state_exists kafka distributed; then
      kafka_state_initialized=true
    else
      kafka_state_initialized=false
      if [[ "${MODE}" == reconcile ]]; then
        echo "Kafka deployment state is missing in reconcile mode" >&2
        exit 1
      fi
    fi
    if ! container_exists feelm-kafka; then
      "${compose[@]}" up -d --no-deps kafka
    fi
    wait_container feelm-kafka running
    preflight >/dev/null
    if [[ "${kafka_state_initialized}" == true ]]; then
      bash "${SCRIPT_DIR}/deploy-kafka-config.sh" "${CI_COMMIT_SHA}"
    else
      bash "${SCRIPT_DIR}/deploy-kafka-config.sh" \
        --initialize-state "${CI_COMMIT_SHA}"
    fi
    ;;
  kafka-topics)
    [[ "${NODE}" == distributed ]] || exit 2
    bash "${SCRIPT_DIR}/ensure-kafka-topics.sh"
    ;;
  spark)
    require_digest INFRA_SPARK_IMAGE
    require_revision INFRA_SPARK_REVISION
    if validate_spark_state "${NODE}"; then
      spark_state_initialized=true
    else
      spark_state_initialized=false
      if [[ "${MODE}" == reconcile ]]; then
        echo "Spark deployment state is missing in reconcile mode: ${NODE}" >&2
        exit 1
      fi
    fi
    if [[ "${NODE}" == distributed ]]; then
      services=(spark-master spark-worker-2)
      containers=(feelm-spark-master feelm-spark-worker-2)
    else
      services=(spark-worker-1)
      containers=(feelm-spark-worker-1)
    fi
    for index in "${!services[@]}"; do
      if container_exists "${containers[$index]}"; then
        running_image="$(docker inspect --format '{{.Config.Image}}' "${containers[$index]}")"
        if [[ "${running_image}" != "${INFRA_SPARK_IMAGE}" ]]; then
          echo "Running Spark image differs from INFRA_SPARK_IMAGE; use Spark CD first" >&2
          exit 1
        fi
        docker start "${containers[$index]}" >/dev/null
      else
        SPARK_IMAGE="${INFRA_SPARK_IMAGE}" \
          "${compose[@]}" up -d --no-deps "${services[$index]}"
      fi
      wait_container "${containers[$index]}" running
    done
    if [[ "${spark_state_initialized}" != true ]]; then
      bash "${SCRIPT_DIR}/deploy-spark-code.sh" initialize "${NODE}" \
        "${INFRA_SPARK_REVISION}" "${INFRA_SPARK_IMAGE}"
    elif [[ "${NODE}" == service ]]; then
      bash "${SCRIPT_DIR}/deploy-spark-code.sh" preflight service \
        "${INFRA_SPARK_REVISION}" "${INFRA_SPARK_IMAGE}"
    fi
    ;;
  spark-finalize)
    [[ "${NODE}" == distributed ]] || exit 2
    require_digest INFRA_SPARK_IMAGE
    require_revision INFRA_SPARK_REVISION
    validate_spark_state distributed || {
      echo "Spark deployment state is missing: distributed" >&2
      exit 1
    }
    bash "${SCRIPT_DIR}/deploy-spark-code.sh" preflight distributed \
      "${INFRA_SPARK_REVISION}" "${INFRA_SPARK_IMAGE}"
    ;;
  backend)
    [[ "${NODE}" == service ]] || exit 2
    require_digest INFRA_BACKEND_IMAGE
    bash "${SCRIPT_DIR}/deploy-backend.sh" "${INFRA_BACKEND_IMAGE}"
    ;;
  consumer)
    [[ "${NODE}" == distributed ]] || exit 2
    require_digest INFRA_CONSUMER_IMAGE
    : "${CONSUMER_COMPOSE_ENV_FILE:?CONSUMER_COMPOSE_ENV_FILE is required}"
    if [[ -f "${CONSUMER_DEPLOY_STATE_FILE:-/var/lib/feelm-deploy/consumer-last-known-good}" ]]; then
      COMPOSE_ENV_FILE="${CONSUMER_COMPOSE_ENV_FILE}" \
        bash "${SCRIPT_DIR}/deploy-consumer.sh" "${INFRA_CONSUMER_IMAGE}"
    elif [[ "${MODE}" == bootstrap ]]; then
      consumer_compose=(
        docker compose
        --project-directory "${PROJECT_ROOT}/infra/compose"
        --env-file "${CONSUMER_COMPOSE_ENV_FILE}"
        -f "${PROJECT_ROOT}/infra/compose/distributed.yml"
        --profile consumer
      )
      CONSUMER_IMAGE="${INFRA_CONSUMER_IMAGE}" \
        "${consumer_compose[@]}" up -d --no-deps consumer
      wait_container feelm-foldin-consumer running
      COMPOSE_ENV_FILE="${CONSUMER_COMPOSE_ENV_FILE}" \
        bash "${SCRIPT_DIR}/deploy-consumer.sh" --initialize-state
    else
      echo "Consumer deployment state is missing in reconcile mode" >&2
      exit 1
    fi
    ;;
  frontend-nginx)
    [[ "${NODE}" == service ]] || exit 2
    require_digest INFRA_BACKEND_IMAGE
    require_digest INFRA_FRONTEND_IMAGE
    BACKEND_IMAGE="${INFRA_BACKEND_IMAGE}" \
      bash "${SCRIPT_DIR}/deploy-frontend.sh" "${INFRA_FRONTEND_IMAGE}"
    ;;
  monitoring)
    [[ "${NODE}" == service ]] || exit 2
    bash "${SCRIPT_DIR}/deploy-monitoring.sh"
    ;;
  auxiliary)
    [[ "${NODE}" == distributed ]] || exit 2
    bash "${SCRIPT_DIR}/deploy-spark-history.sh"
    bash "${SCRIPT_DIR}/deploy-distributed-observability.sh"
    ;;
  verify)
    if [[ "${NODE}" == service ]]; then
      bash "${SCRIPT_DIR}/verify-datastores.sh"
      bash "${SCRIPT_DIR}/verify-postgresql-deployment.sh"
      python3 -I -B "${SCRIPT_DIR}/verify-monitoring-deployment.py"
      for pair in \
        feelm-backend:healthy feelm-frontend:healthy feelm-postgres:healthy \
        feelm-redis:healthy feelm-nginx:running feelm-hdfs-datanode-1:healthy \
        feelm-spark-worker-1:running; do
        wait_container "${pair%%:*}" "${pair##*:}"
      done
    else
      bash "${SCRIPT_DIR}/ensure-kafka-topics.sh"
      python3 -I -B "${SCRIPT_DIR}/verify-spark-history.py" \
        --host "${DISTRIBUTED_NODE_IP}"
      python3 -I -B "${SCRIPT_DIR}/verify-distributed-observability.py" \
        --host "${DISTRIBUTED_NODE_IP}"
      for pair in \
        feelm-hdfs-namenode:healthy feelm-hdfs-datanode-2:healthy \
        feelm-kafka:running feelm-spark-master:running \
        feelm-spark-worker-2:running feelm-foldin-consumer:running; do
        wait_container "${pair%%:*}" "${pair##*:}"
      done
      docker exec feelm-hdfs-namenode hdfs dfsadmin -safemode get \
        | grep -F 'Safe mode is OFF'
      docker exec feelm-hdfs-namenode hdfs dfsadmin -report \
        | grep -F 'Live datanodes (2):'
    fi
    ;;
  *)
    echo "Unsupported Bootstrap/Reconcile step: ${STEP}" >&2
    exit 2
    ;;
esac

echo "Bootstrap/Reconcile step completed: mode=${MODE} node=${NODE} step=${STEP}"
