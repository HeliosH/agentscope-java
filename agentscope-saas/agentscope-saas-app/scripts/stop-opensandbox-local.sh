#!/usr/bin/env bash
# Stops the local enterprise assistant stack started by start-opensandbox-local.sh.
#
# Default:
#   ./agentscope-saas/agentscope-saas-app/scripts/stop-opensandbox-local.sh
#
# Useful overrides:
#   APP_PORT=18080 STOP_DOCKER_DEPS=false ./.../stop-opensandbox-local.sh
set -euo pipefail

APP_PORT="${APP_PORT:-18080}"
STOP_APP="${STOP_APP:-true}"
STOP_DOCKER_DEPS="${STOP_DOCKER_DEPS:-true}"
FORCE_KILL_PORT="${FORCE_KILL_PORT:-false}"
DOCKER_API_VERSION="${DOCKER_API_VERSION:-1.43}"

PG_CONTAINER="${PG_CONTAINER:-saas-pg}"
REDIS_CONTAINER="${REDIS_CONTAINER:-saas-redis}"
MINIO_CONTAINER="${MINIO_CONTAINER:-saas-minio}"
OPENSANDBOX_CONTAINER="${OPENSANDBOX_CONTAINER:-agentscope-opensandbox-server}"

usage() {
  cat <<EOF
Usage: $(basename "$0") [--app-only] [--docker-only] [--port PORT] [--force-port]

Options:
  --app-only     Stop only the SaaS app process.
  --docker-only  Stop only local Docker dependency containers.
  --port PORT    App HTTP port. Default: 18080.
  --force-port   Kill any process listening on the app port, even if it does not
                 look like the local AgentScope SaaS process.
  -h, --help     Show this help.

Environment overrides:
  STOP_APP=false          Do not stop the app process.
  STOP_DOCKER_DEPS=false  Do not stop Docker containers.
  PG_CONTAINER=...        Default: saas-pg.
  REDIS_CONTAINER=...     Default: saas-redis.
  MINIO_CONTAINER=...     Default: saas-minio.
  OPENSANDBOX_CONTAINER=... Default: agentscope-opensandbox-server.
EOF
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --app-only)
      STOP_APP=true
      STOP_DOCKER_DEPS=false
      ;;
    --docker-only)
      STOP_APP=false
      STOP_DOCKER_DEPS=true
      ;;
    --port)
      shift
      if [ "$#" -eq 0 ]; then
        echo "Missing value for --port" >&2
        exit 2
      fi
      APP_PORT="$1"
      ;;
    --force-port)
      FORCE_KILL_PORT=true
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

stop_app_processes() {
  if ! command -v lsof >/dev/null 2>&1; then
    echo "lsof not found; cannot discover app process on port $APP_PORT" >&2
    return 0
  fi

  local pids
  pids="$(lsof -tiTCP:"$APP_PORT" -sTCP:LISTEN 2>/dev/null | sort -u || true)"
  if [ -z "$pids" ]; then
    echo "No app process listening on port $APP_PORT"
    return 0
  fi

  local pid
  for pid in $pids; do
    local cmd
    cmd="$(ps -p "$pid" -o command= 2>/dev/null || true)"
    if [ -z "$cmd" ]; then
      continue
    fi

    if [ "$FORCE_KILL_PORT" = "true" ] \
      || [[ "$cmd" == *"agentscope-saas-app"* ]] \
      || [[ "$cmd" == *"spring-boot:run"* ]] \
      || [[ "$cmd" == *"io.agentscope.saas.SaasApp"* ]]; then
      echo "Stopping app process pid=$pid"
      kill "$pid" >/dev/null 2>&1 || true
    else
      echo "Skipping pid=$pid on port $APP_PORT; command does not look like AgentScope SaaS:"
      echo "  $cmd"
      echo "Use --force-port to stop it anyway."
    fi
  done

  for pid in $pids; do
    local deadline=$((SECONDS + 20))
    while kill -0 "$pid" >/dev/null 2>&1; do
      if [ "$SECONDS" -ge "$deadline" ]; then
        echo "Force killing app process pid=$pid"
        kill -9 "$pid" >/dev/null 2>&1 || true
        break
      fi
      sleep 1
    done
  done
}

stop_container_if_present() {
  local name="$1"
  if DOCKER_API_VERSION="$DOCKER_API_VERSION" docker inspect "$name" >/dev/null 2>&1; then
    local running
    running="$(DOCKER_API_VERSION="$DOCKER_API_VERSION" docker inspect -f '{{.State.Running}}' "$name" 2>/dev/null || echo false)"
    if [ "$running" = "true" ]; then
      echo "Stopping container: $name"
      DOCKER_API_VERSION="$DOCKER_API_VERSION" docker stop "$name" >/dev/null
    else
      echo "Container already stopped: $name"
    fi
  else
    echo "Container not found, skipping: $name" >&2
  fi
}

if [ "$STOP_APP" = "true" ]; then
  stop_app_processes
fi

if [ "$STOP_DOCKER_DEPS" = "true" ]; then
  require_command docker
  stop_container_if_present "$OPENSANDBOX_CONTAINER"
  stop_container_if_present "$REDIS_CONTAINER"
  stop_container_if_present "$MINIO_CONTAINER"
  stop_container_if_present "$PG_CONTAINER"
fi

echo "Local OpenSandbox stack stop completed."
