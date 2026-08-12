#!/usr/bin/env bash
# Stop the AgentScope SaaS app started by start-company-dev.sh.
#
# Usage:
#   ./stop-company-dev.sh
#   SERVER_PORT=8081 ./stop-company-dev.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PID_FILE="$SCRIPT_DIR/company-dev.pid"
PORT="${SERVER_PORT:-8080}"

stop_pid() {
  if [ ! -f "$PID_FILE" ]; then
    echo "No PID file at $PID_FILE"
    return 0
  fi
  local pid; pid="$(cat "$PID_FILE")"
  if kill -0 "$pid" 2>/dev/null; then
    echo "Stopping app pid=$pid"
    kill "$pid" 2>/dev/null || true
    local deadline=$((SECONDS + 20))
    while kill -0 "$pid" 2>/dev/null; do
      if [ "$SECONDS" -ge "$deadline" ]; then
        echo "Force killing pid=$pid"
        kill -9 "$pid" 2>/dev/null || true
        break
      fi
      sleep 1
    done
  else
    echo "Process $pid not running."
  fi
  rm -f "$PID_FILE"
}

stop_pid

# Fallback: kill any lingering listener on the app port (only if lsof exists).
if command -v lsof >/dev/null 2>&1; then
  pids="$(lsof -tiTCP:"$PORT" -sTCP:LISTEN 2>/dev/null | sort -u || true)"
  if [ -n "$pids" ]; then
    for pid in $pids; do
      echo "Killing lingering listener on port $PORT pid=$pid"
      kill "$pid" 2>/dev/null || true
    done
  fi
fi

echo "Stop completed."
