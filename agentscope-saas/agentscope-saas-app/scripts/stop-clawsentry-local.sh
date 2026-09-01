#!/usr/bin/env bash
# Stop the local ClawSentry gateway without removing its trajectory volume.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="$SCRIPT_DIR/../docker/compose-clawsentry.yml"
ENV_FILE="$SCRIPT_DIR/../docker/.env.clawsentry"

command -v docker >/dev/null 2>&1 || { echo "Required command not found: docker" >&2; exit 1; }
if [ -f "$ENV_FILE" ]; then
  docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" down
else
  docker compose -f "$COMPOSE_FILE" down
fi
