#!/usr/bin/env bash
# OpenSandbox release gate for the enterprise assistant.
#
# Validates:
#   1. web/API app readiness
#   2. login/register -> agent -> HITL -> OpenSandbox execution -> workspace download
#   3. admin degradation diagnostics are reachable and have no chat-blocking degraded deps
#   4. Prometheus exposes the key agent/model metrics emitted by the run
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BASE="${BASE:-http://localhost:18080}"
ADMIN_EMAIL="${SANDBOX_GATE_ADMIN_EMAIL:-${SANDBOX_SMOKE_ADMIN_EMAIL:-admin@demo.local}}"
ADMIN_PASSWORD="${SANDBOX_GATE_ADMIN_PASSWORD:-${SANDBOX_SMOKE_ADMIN_PASSWORD:-password}}"
RUN_SMOKE="${SANDBOX_GATE_RUN_SMOKE:-true}"
REQUIRE_PROMETHEUS="${SANDBOX_GATE_REQUIRE_PROMETHEUS:-true}"
TMPDIR="${TMPDIR:-/tmp}"
RUN_ID="$(date +%s)-$$"
ADMIN_JSON="$TMPDIR/opensandbox-gate-admin-$RUN_ID.json"
DEGRADATION_JSON="$TMPDIR/opensandbox-gate-degradation-$RUN_ID.json"
PROM_TEXT="$TMPDIR/opensandbox-gate-prometheus-$RUN_ID.txt"

PASS=0
FAIL=0

ok() {
  echo "  OK  $1"
  PASS=$((PASS + 1))
}

bad() {
  echo "  BAD $1"
  FAIL=$((FAIL + 1))
}

json_get() {
  python3 -c 'import json,sys; print(json.load(sys.stdin).get(sys.argv[1], ""))' "$1"
}

cleanup() {
  rm -f "$ADMIN_JSON" "$DEGRADATION_JSON" "$PROM_TEXT"
}
trap cleanup EXIT

echo "=== OpenSandbox enterprise release gate ==="
echo "  base=$BASE"

HTTP_FRONTEND="$(curl -s -o /dev/null -w '%{http_code}' "$BASE/")"
if [ "$HTTP_FRONTEND" = "200" ]; then
  ok "frontend entry is reachable"
else
  bad "frontend entry returned HTTP $HTTP_FRONTEND"
fi

HTTP_HEALTH="$(curl -s -o /dev/null -w '%{http_code}' "$BASE/actuator/health")"
if [ "$HTTP_HEALTH" = "200" ]; then
  ok "health endpoint is ready"
else
  bad "health endpoint returned HTTP $HTTP_HEALTH"
  exit 1
fi

if [ "$RUN_SMOKE" = "true" ]; then
  export BASE
  export SANDBOX_SMOKE_SANDBOX_TYPE="${SANDBOX_SMOKE_SANDBOX_TYPE:-opensandbox}"
  export SANDBOX_SMOKE_BACKEND_RELEASE_TIMEOUT="${SANDBOX_SMOKE_BACKEND_RELEASE_TIMEOUT:-90}"
  "$SCRIPT_DIR/opensandbox-enterprise-smoke.sh"
  SMOKE_RC=$?
  if [ "$SMOKE_RC" -eq 0 ]; then
    ok "OpenSandbox user task smoke passed"
  else
    bad "OpenSandbox user task smoke failed"
    exit 1
  fi
else
  ok "OpenSandbox user task smoke skipped by SANDBOX_GATE_RUN_SMOKE=false"
fi

ADMIN_BODY="$(EMAIL="$ADMIN_EMAIL" PASSWORD="$ADMIN_PASSWORD" python3 - <<'PY'
import json
import os
print(json.dumps({"email": os.environ["EMAIL"], "password": os.environ["PASSWORD"]}))
PY
)"
if curl -fsS -X POST "$BASE/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d "$ADMIN_BODY" > "$ADMIN_JSON" 2>/dev/null; then
  ADMIN_TOKEN="$(cat "$ADMIN_JSON" | json_get token)"
  ADMIN_ROLE="$(cat "$ADMIN_JSON" | json_get role)"
else
  ADMIN_TOKEN=""
  ADMIN_ROLE=""
fi

if [ -n "$ADMIN_TOKEN" ] && [ "$ADMIN_ROLE" = "admin" ]; then
  ok "admin authenticated for diagnostics"
else
  bad "admin login failed or role is not admin"
  exit 1
fi

if curl -fsS "$BASE/api/admin/degradation?refresh=true" \
  -H "Authorization: Bearer $ADMIN_TOKEN" > "$DEGRADATION_JSON" 2>/dev/null; then
  PY_OUTPUT="$(python3 - "$DEGRADATION_JSON" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as fh:
    payload = json.load(fh)

deps = payload.get("dependencies") or []
bad = [
    dep for dep in deps
    if dep.get("blocksChat") and dep.get("status") in {"degraded", "disabled"}
]
if bad:
    for dep in bad:
        print(f'{dep.get("component")}: {dep.get("status")} - {dep.get("message")}')
    sys.exit(1)
print(f'{len(deps)} dependency status row(s), no chat-blocking degradation')
PY
)"
  if [ $? -eq 0 ]; then
    ok "$PY_OUTPUT"
  else
    bad "chat-blocking degradation detected: $PY_OUTPUT"
  fi
else
  bad "degradation diagnostics endpoint unavailable"
fi

if [ "$REQUIRE_PROMETHEUS" = "true" ]; then
  if curl -fsS "$BASE/actuator/prometheus" > "$PROM_TEXT" 2>/dev/null; then
    if grep -q "saas_agent_chat_stream_duration_seconds_count" "$PROM_TEXT"; then
      ok "Prometheus exposes chat stream duration metric"
    else
      bad "Prometheus missing saas_agent_chat_stream_duration_seconds_count"
    fi
    if grep -q "saas_llm_model_calls_total" "$PROM_TEXT"; then
      ok "Prometheus exposes LLM model call metric"
    else
      bad "Prometheus missing saas_llm_model_calls_total"
    fi
    if grep -q "saas_llm_token_usage_total" "$PROM_TEXT"; then
      ok "Prometheus exposes LLM token usage metric"
    else
      bad "Prometheus missing saas_llm_token_usage_total"
    fi
  else
    bad "Prometheus endpoint unavailable"
  fi
else
  ok "Prometheus checks skipped by SANDBOX_GATE_REQUIRE_PROMETHEUS=false"
fi

echo ""
echo "=== RESULT: PASS=$PASS FAIL=$FAIL ==="
[ "$FAIL" -eq 0 ] && exit 0 || exit 1
