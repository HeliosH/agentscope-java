#!/usr/bin/env bash
# Stops only the independent local LlamaFirewall integration service.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="$SCRIPT_DIR/../docker/compose-llama-firewall-integration.yml"
ENV_FILE="$SCRIPT_DIR/../docker/.env.llama-firewall-integration"

docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" down --remove-orphans
