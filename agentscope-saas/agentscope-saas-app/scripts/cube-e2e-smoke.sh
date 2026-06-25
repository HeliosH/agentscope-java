#!/usr/bin/env bash
# Sandbox smoke gate for the enterprise assistant loop:
#   login/register -> create agent -> HITL confirm -> sandbox executes -> SSE returns output
#   -> release-time workspace projection -> browser/workspace download returns the generated file.
#
# Start the app separately, for example:
#   SERVER_PORT=18080 SAAS_MODEL_TYPE=scripted SAAS_SANDBOX_ENABLED=true \
#   SAAS_SANDBOX_TYPE=cube SAAS_SANDBOX_CUBE_API_URL=http://cubesandbox.dev.comnova.cc:3000 \
#   SAAS_SANDBOX_CUBE_TEMPLATE_ID=tpl-sandbox-code \
#   SAAS_SANDBOX_CUBE_DOMAIN=cubesandbox.dev.comnova.cc \
#   SAAS_SANDBOX_CUBE_INSECURE_SKIP_TLS_VERIFY=true \
#   mvn -pl agentscope-saas/agentscope-saas-app spring-boot:run
#
# Or switch only the backend configuration:
#   SAAS_SANDBOX_TYPE=e2b SAAS_SANDBOX_E2B_API_KEY=... \
#   SAAS_SANDBOX_E2B_TEMPLATE_ID=base
set -uo pipefail

BASE="${BASE:-http://localhost:18080}"
EMAIL="${SANDBOX_SMOKE_EMAIL:-${CUBE_SMOKE_EMAIL:-sandbox-smoke@e2e.test}}"
PASSWORD="${SANDBOX_SMOKE_PASSWORD:-${CUBE_SMOKE_PASSWORD:-pw-sandbox-smoke}}"
MARKER="${SANDBOX_SMOKE_MARKER:-${CUBE_SMOKE_MARKER:-sandbox-smoke-ok}}"
FILE_PATH="${SANDBOX_SMOKE_FILE:-${CUBE_SMOKE_FILE:-generated/report.txt}}"
COMMAND="${SANDBOX_SMOKE_COMMAND:-${CUBE_SMOKE_COMMAND:-mkdir -p generated && printf '%s\n' $MARKER > $FILE_PATH && cat $FILE_PATH}}"
TIMEOUT="${SANDBOX_SMOKE_TIMEOUT:-${CUBE_SMOKE_TIMEOUT:-120}}"
TMPDIR="${TMPDIR:-/tmp}"
RUN_ID="$(date +%s)-$$"
SSE1="$TMPDIR/cube-smoke-1-$RUN_ID.sse"
SSE2="$TMPDIR/cube-smoke-2-$RUN_ID.sse"
CONFIRM_JSON="$TMPDIR/cube-smoke-confirm-$RUN_ID.json"
DOWNLOAD_FILE="$TMPDIR/cube-smoke-download-$RUN_ID.txt"

PASS=0
FAIL=0
AGID=""
TOK=""

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
  if [ -n "$TOK" ] && [ -n "$AGID" ]; then
    curl -s -o /dev/null -X DELETE "$BASE/api/agents/$AGID" -H "Authorization: Bearer $TOK" || true
  fi
  rm -f "$SSE1" "$SSE2" "$CONFIRM_JSON" "$DOWNLOAD_FILE"
}
trap cleanup EXIT

echo "=== Sandbox enterprise smoke ==="
echo "  base=$BASE"
echo "  command=$COMMAND"
echo "  generated_file=$FILE_PATH"

HTTP_HEALTH="$(curl -s -o /dev/null -w '%{http_code}' "$BASE/actuator/health")"
if [ "$HTTP_HEALTH" = "200" ]; then
  ok "health endpoint is ready"
else
  bad "health endpoint returned HTTP $HTTP_HEALTH"
  exit 1
fi

LOGIN_BODY="$(EMAIL="$EMAIL" PASSWORD="$PASSWORD" python3 - <<'PY'
import json
import os
print(json.dumps({"email": os.environ["EMAIL"], "password": os.environ["PASSWORD"]}))
PY
)"
REG_BODY="$(EMAIL="$EMAIL" PASSWORD="$PASSWORD" python3 - <<'PY'
import json
import os
print(json.dumps({
    "email": os.environ["EMAIL"],
    "password": os.environ["PASSWORD"],
    "displayName": "Cube Smoke",
}))
PY
)"
AUTH_JSON="$(curl -fsS -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' -d "$LOGIN_BODY" 2>/dev/null)"
if [ $? -ne 0 ] || [ -z "$AUTH_JSON" ]; then
  AUTH_JSON="$(curl -fsS -X POST "$BASE/api/auth/register" -H 'Content-Type: application/json' -d "$REG_BODY" 2>/dev/null)"
fi
TOK="$(printf '%s' "$AUTH_JSON" | json_get token)"
ORG="$(printf '%s' "$AUTH_JSON" | json_get orgId)"
if [ -n "$TOK" ]; then
  ok "user authenticated org=$ORG"
else
  bad "login/register failed: $AUTH_JSON"
  exit 1
fi

AGENT_BODY="$(python3 - <<PY
import json
print(json.dumps({
  "name": "CubeSmoke",
  "description": "CubeSandbox smoke agent",
  "sysPrompt": "Execute the requested shell command exactly and return the output.",
  "maxIters": 8,
  "tools": ["execute"],
  "workspacePath": "agents/cube-smoke"
}))
PY
)"
AGENT_JSON="$(curl -fsS -X POST "$BASE/api/agents" -H "Authorization: Bearer $TOK" -H 'Content-Type: application/json' -d "$AGENT_BODY")"
AGID="$(printf '%s' "$AGENT_JSON" | json_get id)"
if [ -n "$AGID" ]; then
  ok "agent created id=$AGID"
else
  bad "agent create failed: $AGENT_JSON"
  exit 1
fi

CHAT_BODY="$(COMMAND="$COMMAND" python3 - <<'PY'
import json
import os
print(json.dumps({"message": os.environ["COMMAND"]}))
PY
)"
curl -s -N --max-time "$TIMEOUT" -X POST "$BASE/api/agents/$AGID/chat/stream" \
  -H "Authorization: Bearer $TOK" -H 'Content-Type: application/json' \
  -d "$CHAT_BODY" > "$SSE1" 2>/dev/null

if grep -q "data:" "$SSE1"; then
  ok "initial chat returned SSE"
else
  bad "initial chat returned no SSE"
  sed -n '1,20p' "$SSE1"
  exit 1
fi

python3 - "$SSE1" "$CONFIRM_JSON" <<'PY'
import json
import sys

sse_path, out_path = sys.argv[1], sys.argv[2]
events = []
with open(sse_path, encoding="utf-8") as fh:
    for line in fh:
        if not line.startswith("data:"):
            continue
        payload = line[5:].strip()
        if not payload:
            continue
        try:
            events.append(json.loads(payload))
        except json.JSONDecodeError:
            pass

for ev in events:
    if ev.get("type") == "CUSTOM" and ev.get("name") == "require_user_confirm":
        value = ev.get("value") or {}
        results = []
        for call in value.get("toolCalls") or []:
            tool_call_id = call.get("id") or call.get("toolCallId")
            tool_name = call.get("name") or call.get("toolCallName")
            results.append({
                "toolCallId": tool_call_id,
                "toolName": tool_name,
                "confirmed": True,
                "input": call.get("input") or {},
            })
        if results:
            with open(out_path, "w", encoding="utf-8") as out:
                json.dump({"sessionId": ev.get("threadId"), "confirmResults": results}, out)
            sys.exit(0)

sys.exit(2)
PY
PY_RC=$?

if [ "$PY_RC" -eq 0 ]; then
  ok "HITL confirmation requested"
  curl -s -N --max-time "$TIMEOUT" -X POST "$BASE/api/agents/$AGID/chat/stream" \
    -H "Authorization: Bearer $TOK" -H 'Content-Type: application/json' \
    --data-binary "@$CONFIRM_JSON" > "$SSE2" 2>/dev/null
elif grep -q "$MARKER" "$SSE1"; then
  cp "$SSE1" "$SSE2"
  ok "sandbox completed without HITL prompt"
else
  bad "no HITL confirmation and marker not returned"
  python3 - "$SSE1" <<'PY'
import json
import sys
for line in open(sys.argv[1], encoding="utf-8"):
    if not line.startswith("data:"):
        continue
    try:
        ev = json.loads(line[5:].strip())
    except Exception:
        continue
    if ev.get("type") == "CUSTOM" and ev.get("name") == "error":
        print("    error:", (ev.get("value") or {}).get("message"))
PY
  exit 1
fi

if grep -q "$MARKER" "$SSE2"; then
  ok "sandbox execution output returned to SSE"
else
  bad "sandbox execution output missing"
  python3 - "$SSE2" <<'PY'
import json
import sys
for line in open(sys.argv[1], encoding="utf-8"):
    if not line.startswith("data:"):
        continue
    try:
        ev = json.loads(line[5:].strip())
    except Exception:
        continue
    if ev.get("type") == "CUSTOM" and ev.get("name") == "error":
        print("    error:", (ev.get("value") or {}).get("message"))
    elif ev.get("type") in {"TOOL_CALL_RESULT", "TEXT_MESSAGE_CONTENT"}:
        print("    output:", ev.get("content") or ev.get("delta") or "")
PY
fi

HTTP_DOWNLOAD="$(curl -s -o "$DOWNLOAD_FILE" -w '%{http_code}' \
  "$BASE/api/agents/$AGID/workspace/file/download?path=$FILE_PATH" \
  -H "Authorization: Bearer $TOK")"
if [ "$HTTP_DOWNLOAD" = "200" ] && grep -q "$MARKER" "$DOWNLOAD_FILE"; then
  ok "workspace download returns release-projected sandbox file"
else
  bad "workspace download failed for release-projected file (HTTP $HTTP_DOWNLOAD)"
  if [ -s "$DOWNLOAD_FILE" ]; then
    sed -n '1,20p' "$DOWNLOAD_FILE"
  fi
fi

echo ""
echo "=== RESULT: PASS=$PASS FAIL=$FAIL ==="
[ "$FAIL" -eq 0 ] && exit 0 || exit 1
