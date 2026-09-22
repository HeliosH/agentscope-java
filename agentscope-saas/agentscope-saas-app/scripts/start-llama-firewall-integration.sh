#!/usr/bin/env bash
# Starts the independent LlamaFirewall service used only for local integration testing.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="$SCRIPT_DIR/../docker/compose-llama-firewall-integration.yml"
ENV_FILE="$SCRIPT_DIR/../docker/.env.llama-firewall-integration"

command -v docker >/dev/null 2>&1 || { echo "Required command not found: docker" >&2; exit 1; }
docker info >/dev/null 2>&1 || { echo "Docker daemon is not available." >&2; exit 1; }
if [ ! -f "$ENV_FILE" ]; then
  echo "Missing $ENV_FILE. Copy docker/.env.llama-firewall-integration.example and configure it." >&2
  exit 1
fi

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a
if [ -z "${LF_AUTH_TOKEN:-}" ] || [ "$LF_AUTH_TOKEN" = "CHANGE_ME_TO_A_RANDOM_TOKEN" ]; then
  echo "Set a non-empty LF_AUTH_TOKEN in $ENV_FILE." >&2
  exit 1
fi

docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" up -d --build
PORT="${LLAMA_FIREWALL_HTTP_PORT:-18082}"
"$SCRIPT_DIR/llama-firewall-integration-smoke.sh" "http://localhost:${PORT}" "$LF_AUTH_TOKEN"
echo "Independent LlamaFirewall integration service ready: http://localhost:${PORT}/health"
