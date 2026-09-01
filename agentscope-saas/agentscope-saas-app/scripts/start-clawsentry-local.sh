#!/usr/bin/env bash
# Build and start the local ClawSentry gateway used by the SaaS app.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="$SCRIPT_DIR/../docker/compose-clawsentry.yml"
ENV_FILE="$SCRIPT_DIR/../docker/.env.clawsentry"

command -v docker >/dev/null 2>&1 || { echo "Required command not found: docker" >&2; exit 1; }
docker info >/dev/null 2>&1 || { echo "Docker daemon is not available." >&2; exit 1; }

if [ ! -f "$ENV_FILE" ]; then
  echo "Missing $ENV_FILE. Copy docker/.env.clawsentry.example and set CS_AUTH_TOKEN." >&2
  exit 1
fi

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a
if [ -z "${CS_AUTH_TOKEN:-}" ] || [ "$CS_AUTH_TOKEN" = "CHANGE_ME_TO_A_RANDOM_TOKEN" ]; then
  echo "Set a non-empty CS_AUTH_TOKEN in $ENV_FILE before starting ClawSentry." >&2
  exit 1
fi

docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" up -d --build
PORT="${CLAWSENTRY_HTTP_PORT:-18081}"
echo "ClawSentry is starting: http://localhost:${PORT}/health"
echo "Stop with: $SCRIPT_DIR/stop-clawsentry-local.sh"
