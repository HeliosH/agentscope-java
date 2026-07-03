#!/usr/bin/env bash
# OpenSandbox application-level enterprise smoke gate.
#
# This validates the same user-facing flow as the Cube/E2B smoke script:
#   login/register -> create agent -> HITL confirm -> sandbox executes -> SSE output
#   -> release-time projection -> workspace download.
#
# Preconditions:
#   - The SaaS app is already running.
#   - The app is configured with SAAS_SANDBOX_TYPE=opensandbox.
#   - For direct provider lifecycle API validation, run opensandbox-runtime-lifecycle.sh separately.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

export SANDBOX_SMOKE_EMAIL="${SANDBOX_SMOKE_EMAIL:-opensandbox-smoke-$(date +%s)@e2e.test}"
export SANDBOX_SMOKE_PASSWORD="${SANDBOX_SMOKE_PASSWORD:-pw-opensandbox-smoke}"
export SANDBOX_SMOKE_MARKER="${SANDBOX_SMOKE_MARKER:-opensandbox-enterprise-ok}"
export SANDBOX_SMOKE_FILE="${SANDBOX_SMOKE_FILE:-generated/opensandbox-report.txt}"
if [ -z "${SANDBOX_SMOKE_COMMAND:-}" ]; then
  SANDBOX_SMOKE_COMMAND="mkdir -p generated && printf '%s\n' $SANDBOX_SMOKE_MARKER > $SANDBOX_SMOKE_FILE && cat $SANDBOX_SMOKE_FILE"
fi
export SANDBOX_SMOKE_COMMAND
export SANDBOX_SMOKE_TIMEOUT="${SANDBOX_SMOKE_TIMEOUT:-180}"

echo "=== OpenSandbox enterprise application smoke ==="
echo "  base=${BASE:-http://localhost:18080}"
echo "  marker=$SANDBOX_SMOKE_MARKER"

exec "$SCRIPT_DIR/cube-e2e-smoke.sh"
