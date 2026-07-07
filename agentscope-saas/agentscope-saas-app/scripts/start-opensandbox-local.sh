#!/usr/bin/env bash
# Starts the local enterprise assistant stack with OpenSandbox.
#
# Default mode:
#   ./agentscope-saas/agentscope-saas-app/scripts/start-opensandbox-local.sh
#   Then open http://localhost:18080
#
# Smoke mode:
#   ./agentscope-saas/agentscope-saas-app/scripts/start-opensandbox-local.sh --smoke
#
# Useful overrides:
#   APP_PORT=18081 START_DOCKER_DEPS=false MAVEN_OFFLINE=false ./.../start-opensandbox-local.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
APP_MODULE="agentscope-saas/agentscope-saas-app"

APP_PORT="${APP_PORT:-18080}"
BASE_URL="${BASE_URL:-http://localhost:$APP_PORT}"
START_DOCKER_DEPS="${START_DOCKER_DEPS:-true}"
START_DOCKER_DAEMON="${START_DOCKER_DAEMON:-true}"
RUN_SMOKE="${RUN_SMOKE:-false}"
MAVEN_OFFLINE="${MAVEN_OFFLINE:-true}"
MAVEN_PREPARE_ARGS=(
  -DskipTests
  -Dmaven.javadoc.skip=true
  -Dmaven.source.skip=true
  -Djacoco.skip=true
)
WAIT_TIMEOUT_SECONDS="${WAIT_TIMEOUT_SECONDS:-90}"
DOCKER_START_TIMEOUT_SECONDS="${DOCKER_START_TIMEOUT_SECONDS:-120}"

PG_CONTAINER="${PG_CONTAINER:-saas-pg}"
REDIS_CONTAINER="${REDIS_CONTAINER:-saas-redis}"
MINIO_CONTAINER="${MINIO_CONTAINER:-saas-minio}"
OPENSANDBOX_CONTAINER="${OPENSANDBOX_CONTAINER:-agentscope-opensandbox-server}"
DOCKER_API_VERSION="${DOCKER_API_VERSION:-1.43}"

SAAS_DB_NAME="${SAAS_DB_NAME:-agentscope_saas_e2e}"
SAAS_DB_HOST="${SAAS_DB_HOST:-localhost}"
SAAS_DB_PORT="${SAAS_DB_PORT:-5432}"
SAAS_REDIS_HOST="${SAAS_REDIS_HOST:-localhost}"
SAAS_REDIS_PORT="${SAAS_REDIS_PORT:-6379}"
SAAS_MINIO_HOST="${SAAS_MINIO_HOST:-localhost}"
SAAS_MINIO_PORT="${SAAS_MINIO_PORT:-9000}"
OPENSANDBOX_HOST="${OPENSANDBOX_HOST:-127.0.0.1}"
OPENSANDBOX_PORT="${OPENSANDBOX_PORT:-18081}"

usage() {
  cat <<EOF
Usage: $(basename "$0") [--smoke] [--no-docker] [--port PORT]

Options:
  --smoke      Start the app, run opensandbox-enterprise-smoke.sh, then stop.
  --no-docker  Do not start local Docker dependency containers.
  --port PORT  App HTTP port. Default: 18080.
  -h, --help   Show this help.

Environment overrides:
  START_DOCKER_DEPS=false   Skip docker start.
  START_DOCKER_DAEMON=false Do not auto-start Docker Desktop/daemon.
  MAVEN_OFFLINE=false       Run Maven without -o.
  SAAS_DB_NAME=...          Default: agentscope_saas_e2e.
  PG_CONTAINER=...          Default: saas-pg.
  REDIS_CONTAINER=...       Default: saas-redis.
  MINIO_CONTAINER=...       Default: saas-minio.
  OPENSANDBOX_CONTAINER=... Default: agentscope-opensandbox-server.
EOF
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --smoke)
      RUN_SMOKE=true
      ;;
    --no-docker)
      START_DOCKER_DEPS=false
      ;;
    --port)
      shift
      if [ "$#" -eq 0 ]; then
        echo "Missing value for --port" >&2
        exit 2
      fi
      APP_PORT="$1"
      BASE_URL="http://localhost:$APP_PORT"
      ;;
    -h | --help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
  shift
done

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Required command not found: $1" >&2
    exit 1
  fi
}

docker_daemon_ready() {
  DOCKER_API_VERSION="$DOCKER_API_VERSION" docker info >/dev/null 2>&1
}

ensure_docker_daemon() {
  if docker_daemon_ready; then
    echo "Ready: Docker daemon"
    return 0
  fi

  if [ "$START_DOCKER_DAEMON" != "true" ]; then
    echo "Docker daemon is not running. Start Docker, or set START_DOCKER_DAEMON=true." >&2
    exit 1
  fi

  case "$(uname -s)" in
    Darwin)
      if command -v open >/dev/null 2>&1; then
        echo "Docker daemon is not running; opening Docker Desktop..."
        open -a Docker >/dev/null 2>&1 || true
      else
        echo "Docker daemon is not running and 'open' is unavailable." >&2
        exit 1
      fi
      ;;
    Linux)
      echo "Docker daemon is not running." >&2
      echo "Start it manually, for example: sudo systemctl start docker" >&2
      exit 1
      ;;
    *)
      echo "Docker daemon is not running. Start Docker manually and rerun this script." >&2
      exit 1
      ;;
  esac

  local deadline=$((SECONDS + DOCKER_START_TIMEOUT_SECONDS))
  while ! docker_daemon_ready; do
    if [ "$SECONDS" -ge "$deadline" ]; then
      echo "Timed out waiting for Docker daemon after ${DOCKER_START_TIMEOUT_SECONDS}s" >&2
      exit 1
    fi
    sleep 2
  done
  echo "Ready: Docker daemon"
}

start_container_if_present() {
  local name="$1"
  if DOCKER_API_VERSION="$DOCKER_API_VERSION" docker inspect "$name" >/dev/null 2>&1; then
    echo "Starting container: $name"
    DOCKER_API_VERSION="$DOCKER_API_VERSION" docker start "$name" >/dev/null
  else
    echo "Container not found, skipping docker start: $name" >&2
  fi
}

wait_tcp() {
  local host="$1"
  local port="$2"
  local label="$3"
  local deadline=$((SECONDS + WAIT_TIMEOUT_SECONDS))
  while ! (echo >"/dev/tcp/$host/$port") >/dev/null 2>&1; do
    if [ "$SECONDS" -ge "$deadline" ]; then
      echo "Timed out waiting for $label at $host:$port" >&2
      exit 1
    fi
    sleep 1
  done
  echo "Ready: $label ($host:$port)"
}

wait_http() {
  local url="$1"
  local label="$2"
  local deadline=$((SECONDS + WAIT_TIMEOUT_SECONDS))
  while ! curl -fsS "$url" >/dev/null 2>&1; do
    if [ "$SECONDS" -ge "$deadline" ]; then
      echo "Timed out waiting for $label at $url" >&2
      exit 1
    fi
    sleep 1
  done
  echo "Ready: $label ($url)"
}

export_app_env() {
  export SERVER_PORT="$APP_PORT"

  export SAAS_DB_URL="${SAAS_DB_URL:-jdbc:postgresql://$SAAS_DB_HOST:$SAAS_DB_PORT/$SAAS_DB_NAME}"
  export SAAS_DB_ADMIN_URL="${SAAS_DB_ADMIN_URL:-$SAAS_DB_URL}"
  export SAAS_DB_USER="${SAAS_DB_USER:-app}"
  export SAAS_DB_PASSWORD="${SAAS_DB_PASSWORD:-agentscope}"
  export SAAS_DB_ADMIN_USER="${SAAS_DB_ADMIN_USER:-agentscope}"
  export SAAS_DB_ADMIN_PASSWORD="${SAAS_DB_ADMIN_PASSWORD:-agentscope}"

  export SAAS_MODEL_TYPE="${SAAS_MODEL_TYPE:-scripted}"

  export SAAS_REDIS_ENABLED="${SAAS_REDIS_ENABLED:-true}"
  export SAAS_REDIS_URI="${SAAS_REDIS_URI:-redis://$SAAS_REDIS_HOST:$SAAS_REDIS_PORT}"

  export SAAS_SANDBOX_ENABLED="${SAAS_SANDBOX_ENABLED:-true}"
  export SAAS_SANDBOX_TYPE="${SAAS_SANDBOX_TYPE:-opensandbox}"
  export SAAS_SANDBOX_OPENSANDBOX_API_BASE_URL="${SAAS_SANDBOX_OPENSANDBOX_API_BASE_URL:-http://$OPENSANDBOX_HOST:$OPENSANDBOX_PORT}"
  export SAAS_SANDBOX_OPENSANDBOX_IMAGE="${SAAS_SANDBOX_OPENSANDBOX_IMAGE:-ubuntu:latest}"
  export SAAS_SANDBOX_WORKSPACE_ROOT="${SAAS_SANDBOX_WORKSPACE_ROOT:-/workspace}"
  export SAAS_SANDBOX_IDLE_TTL="${SAAS_SANDBOX_IDLE_TTL:-60}"
  export SAAS_SANDBOX_RECONCILIATION_FIXED_DELAY_SECONDS="${SAAS_SANDBOX_RECONCILIATION_FIXED_DELAY_SECONDS:-1}"
  export SAAS_SANDBOX_RECONCILIATION_BATCH_SIZE="${SAAS_SANDBOX_RECONCILIATION_BATCH_SIZE:-200}"
  export SAAS_SANDBOX_SNAPSHOT_BACKEND="${SAAS_SANDBOX_SNAPSHOT_BACKEND:-minio}"
  export SAAS_SANDBOX_MINIO_ENDPOINT="${SAAS_SANDBOX_MINIO_ENDPOINT:-http://$SAAS_MINIO_HOST:$SAAS_MINIO_PORT}"
  export SAAS_SANDBOX_MINIO_ACCESS_KEY="${SAAS_SANDBOX_MINIO_ACCESS_KEY:-minioadmin}"
  export SAAS_SANDBOX_MINIO_SECRET_KEY="${SAAS_SANDBOX_MINIO_SECRET_KEY:-minioadmin}"
  export SAAS_SANDBOX_MINIO_BUCKET="${SAAS_SANDBOX_MINIO_BUCKET:-agentscope-saas-opensandbox}"

  export SAAS_FILE_STORE_ENABLED="${SAAS_FILE_STORE_ENABLED:-true}"
  export SAAS_FILE_STORE_BACKEND="${SAAS_FILE_STORE_BACKEND:-minio}"
  export SAAS_FILE_STORE_MINIO_ENDPOINT="${SAAS_FILE_STORE_MINIO_ENDPOINT:-http://$SAAS_MINIO_HOST:$SAAS_MINIO_PORT}"
  export SAAS_FILE_STORE_MINIO_ACCESS_KEY="${SAAS_FILE_STORE_MINIO_ACCESS_KEY:-minioadmin}"
  export SAAS_FILE_STORE_MINIO_SECRET_KEY="${SAAS_FILE_STORE_MINIO_SECRET_KEY:-minioadmin}"
  export SAAS_FILE_STORE_MINIO_BUCKET="${SAAS_FILE_STORE_MINIO_BUCKET:-agentscope-saas-opensandbox-files}"
}

maven_cmd() {
  local prepare=(mvn)
  local run=(mvn)
  if [ "$MAVEN_OFFLINE" = "true" ]; then
    prepare+=("-o")
    run+=("-o")
  fi
  prepare+=("-pl" "$APP_MODULE" "-am" "${MAVEN_PREPARE_ARGS[@]}" "install")
  run+=("-pl" "$APP_MODULE" "spring-boot:run")
  printf '%q ' "${prepare[@]}"
  printf '&& '
  printf '%q ' "${run[@]}"
}

prepare_reactor_dependencies() {
  cd "$REPO_ROOT"
  if [ "$MAVEN_OFFLINE" = "true" ]; then
    mvn -o -pl "$APP_MODULE" -am "${MAVEN_PREPARE_ARGS[@]}" install
  else
    mvn -pl "$APP_MODULE" -am "${MAVEN_PREPARE_ARGS[@]}" install
  fi
}

run_app_foreground() {
  echo
  echo "Starting AgentScope SaaS app"
  echo "  URL: $BASE_URL"
  echo "  Login: admin@demo.local / password"
  echo "  Model mode: $SAAS_MODEL_TYPE"
  echo "  Sandbox: $SAAS_SANDBOX_TYPE ($SAAS_SANDBOX_OPENSANDBOX_API_BASE_URL)"
  echo
  cd "$REPO_ROOT"
  prepare_reactor_dependencies
  if [ "$MAVEN_OFFLINE" = "true" ]; then
    exec mvn -o -pl "$APP_MODULE" spring-boot:run
  else
    exec mvn -pl "$APP_MODULE" spring-boot:run
  fi
}

run_smoke_mode() {
  local app_pid=""
  cleanup() {
    if [ -n "$app_pid" ] && kill -0 "$app_pid" >/dev/null 2>&1; then
      kill "$app_pid" >/dev/null 2>&1 || true
      wait "$app_pid" >/dev/null 2>&1 || true
    fi
  }
  trap cleanup EXIT

  echo "Starting app for smoke validation..."
  cd "$REPO_ROOT"
  prepare_reactor_dependencies
  if [ "$MAVEN_OFFLINE" = "true" ]; then
    mvn -o -pl "$APP_MODULE" spring-boot:run &
  else
    mvn -pl "$APP_MODULE" spring-boot:run &
  fi
  app_pid="$!"
  wait_http "$BASE_URL/actuator/health" "SaaS app"

  BASE="$BASE_URL" \
  SANDBOX_SMOKE_BACKEND_RELEASE_TIMEOUT="${SANDBOX_SMOKE_BACKEND_RELEASE_TIMEOUT:-120}" \
  SANDBOX_SMOKE_TIMEOUT="${SANDBOX_SMOKE_TIMEOUT:-180}" \
    "$SCRIPT_DIR/opensandbox-enterprise-smoke.sh"
}

require_command bash
require_command curl
require_command mvn

if [ "$START_DOCKER_DEPS" = "true" ]; then
  require_command docker
  ensure_docker_daemon
  start_container_if_present "$PG_CONTAINER"
  start_container_if_present "$REDIS_CONTAINER"
  start_container_if_present "$MINIO_CONTAINER"
  start_container_if_present "$OPENSANDBOX_CONTAINER"
fi

wait_tcp "$SAAS_DB_HOST" "$SAAS_DB_PORT" "PostgreSQL"
wait_tcp "$SAAS_REDIS_HOST" "$SAAS_REDIS_PORT" "Redis"
wait_tcp "$SAAS_MINIO_HOST" "$SAAS_MINIO_PORT" "MinIO"
wait_tcp "$OPENSANDBOX_HOST" "$OPENSANDBOX_PORT" "OpenSandbox"

export_app_env

echo
echo "Effective local settings:"
echo "  app:        $BASE_URL"
echo "  database:   $SAAS_DB_URL"
echo "  redis:      $SAAS_REDIS_URI"
echo "  minio:      $SAAS_FILE_STORE_MINIO_ENDPOINT"
echo "  opensandbox:$SAAS_SANDBOX_OPENSANDBOX_API_BASE_URL"
echo "  maven:      $(maven_cmd)"

if [ "$RUN_SMOKE" = "true" ]; then
  run_smoke_mode
else
  run_app_foreground
fi
