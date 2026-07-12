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
BACKEND_RELEASE_TIMEOUT="${SANDBOX_SMOKE_BACKEND_RELEASE_TIMEOUT:-${CUBE_SMOKE_BACKEND_RELEASE_TIMEOUT:-0}}"
BACKEND_RELEASE_POLL_SECONDS="${SANDBOX_SMOKE_BACKEND_RELEASE_POLL_SECONDS:-2}"
SANDBOX_TYPE_FILTER="${SANDBOX_SMOKE_SANDBOX_TYPE:-}"
ADMIN_EMAIL="${SANDBOX_SMOKE_ADMIN_EMAIL:-${CUBE_SMOKE_ADMIN_EMAIL:-admin@demo.local}}"
ADMIN_PASSWORD="${SANDBOX_SMOKE_ADMIN_PASSWORD:-${CUBE_SMOKE_ADMIN_PASSWORD:-password}}"
TMPDIR="${TMPDIR:-/tmp}"
RUN_ID="$(date +%s)-$$"
SSE1="$TMPDIR/cube-smoke-1-$RUN_ID.sse"
SSE2="$TMPDIR/cube-smoke-2-$RUN_ID.sse"
CONFIRM_JSON="$TMPDIR/cube-smoke-confirm-$RUN_ID.json"
DOWNLOAD_FILE="$TMPDIR/cube-smoke-download-$RUN_ID.txt"
UPLOAD_SOURCE="$TMPDIR/cube-smoke-upload-$RUN_ID.txt"
UPLOAD_JSON="$TMPDIR/cube-smoke-upload-$RUN_ID.json"
UPLOAD_DOWNLOAD="$TMPDIR/cube-smoke-upload-download-$RUN_ID.txt"
WORKSPACE_JSON="$TMPDIR/cube-smoke-workspace-$RUN_ID.json"
SESSION_JSON="$TMPDIR/cube-smoke-session-$RUN_ID.json"
TURN_WINDOW_JSON="$TMPDIR/cube-smoke-turn-window-$RUN_ID.json"
OLDER_TURNS_JSON="$TMPDIR/cube-smoke-older-turns-$RUN_ID.json"
SANDBOX_LIST_JSON="$TMPDIR/cube-smoke-sandboxes-$RUN_ID.json"

PASS=0
FAIL=0
AGID=""
TOK=""
ADMIN_TOK=""

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

resolve_admin_token() {
  if [ -n "$ADMIN_TOK" ]; then
    return 0
  fi
  if [ -z "$ADMIN_EMAIL" ] || [ -z "$ADMIN_PASSWORD" ]; then
    return 1
  fi
  local admin_body
  admin_body="$(EMAIL="$ADMIN_EMAIL" PASSWORD="$ADMIN_PASSWORD" python3 - <<'PY'
import json
import os
print(json.dumps({"email": os.environ["EMAIL"], "password": os.environ["PASSWORD"]}))
PY
)"
  local admin_json
  admin_json="$(curl -fsS -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' -d "$admin_body" 2>/dev/null)"
  if [ $? -ne 0 ] || [ -z "$admin_json" ]; then
    return 1
  fi
  ADMIN_TOK="$(printf '%s' "$admin_json" | json_get token)"
  local admin_role
  admin_role="$(printf '%s' "$admin_json" | json_get role)"
  [ -n "$ADMIN_TOK" ] && [ "$admin_role" = "admin" ]
}

cleanup() {
  if [ -n "$TOK" ] && [ -n "$AGID" ]; then
    curl -s -o /dev/null -X DELETE "$BASE/api/agents/$AGID" -H "Authorization: Bearer $TOK" || true
  fi
  rm -f "$SSE1" "$SSE2" "$CONFIRM_JSON" "$DOWNLOAD_FILE" \
    "$UPLOAD_SOURCE" "$UPLOAD_JSON" "$UPLOAD_DOWNLOAD" "$WORKSPACE_JSON" \
    "$SESSION_JSON" "$TURN_WINDOW_JSON" "$OLDER_TURNS_JSON" "$SANDBOX_LIST_JSON"
}
trap cleanup EXIT

wait_backend_release() {
  if [ "$BACKEND_RELEASE_TIMEOUT" -le 0 ]; then
    return 0
  fi
  local deadline
  deadline="$(($(date +%s) + BACKEND_RELEASE_TIMEOUT))"
  if ! resolve_admin_token; then
    bad "admin sandbox inventory token unavailable"
    return 1
  fi
  local admin_url
  admin_url="$BASE/api/admin/sandboxes?limit=100"
  if [ -n "$SANDBOX_TYPE_FILTER" ]; then
    admin_url="$admin_url&sandboxType=$SANDBOX_TYPE_FILTER"
  fi

  while :; do
    if curl -fsS -o "$SANDBOX_LIST_JSON" "$admin_url" -H "Authorization: Bearer $ADMIN_TOK" 2>/dev/null; then
      PY_OUTPUT="$(AGID="$AGID" python3 - "$SANDBOX_LIST_JSON" <<'PY'
import json
import os
import sys

path = sys.argv[1]
agent_id = os.environ["AGID"]
terminal = {"succeeded", "unsupported", "no_external_id", "skipped"}
with open(path, encoding="utf-8") as fh:
    rows = json.load(fh)
rows = [row for row in rows if row.get("agentId") == agent_id]
if not rows:
    print("no sandbox tracking rows for agent")
    sys.exit(2)
active = [row for row in rows if row.get("status") == "active"]
failed = [row for row in rows if row.get("backendReleaseStatus") == "failed"]
pending = [
    row for row in rows
    if row.get("externalId")
    and row.get("backendReleaseStatus") not in terminal
]
if active:
    print(f"{len(active)} active sandbox tracking row(s)")
    sys.exit(2)
if failed:
    first = failed[0]
    print(
        "backend release failed for "
        + str(first.get("id"))
        + ": "
        + str(first.get("backendReleaseError") or "")
    )
    sys.exit(1)
if pending:
    statuses = sorted({str(row.get("backendReleaseStatus") or "<empty>") for row in pending})
    print("backend release pending: " + ", ".join(statuses))
    sys.exit(2)
print(f"{len(rows)} sandbox tracking row(s) released with terminal backend release")
PY
)"
      PY_RC=$?
      if [ "$PY_RC" -eq 0 ]; then
        ok "$PY_OUTPUT"
        return 0
      fi
      if [ "$PY_RC" -eq 1 ]; then
        bad "$PY_OUTPUT"
        return 1
      fi
    else
      PY_OUTPUT="admin sandbox inventory unavailable"
    fi

    if [ "$(date +%s)" -ge "$deadline" ]; then
      bad "backend release did not reach terminal status: $PY_OUTPUT"
      return 1
    fi
    sleep "$BACKEND_RELEASE_POLL_SECONDS"
  done
}

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

UPLOAD_MARKER="browser-upload-$RUN_ID"
printf '%s\n' "$UPLOAD_MARKER" > "$UPLOAD_SOURCE"
HTTP_UPLOAD="$(curl -s -o "$UPLOAD_JSON" -w '%{http_code}' -X POST \
  "$BASE/api/agents/$AGID/workspace/file/upload?path=uploads/browser-upload.txt" \
  -H "Authorization: Bearer $TOK" -F "file=@$UPLOAD_SOURCE;type=text/plain")"
UPLOAD_PATH="$(printf '%s' "$(cat "$UPLOAD_JSON" 2>/dev/null)" | json_get path)"
if [ "$HTTP_UPLOAD" = "201" ] && [ "$UPLOAD_PATH" = "uploads/browser-upload.txt" ]; then
  ok "browser multipart upload is cataloged"
else
  bad "browser multipart upload failed (HTTP $HTTP_UPLOAD): $(cat "$UPLOAD_JSON" 2>/dev/null)"
fi

HTTP_UPLOAD_DOWNLOAD="$(curl -s -o "$UPLOAD_DOWNLOAD" -w '%{http_code}' \
  "$BASE/api/agents/$AGID/workspace/file/download?path=uploads/browser-upload.txt" \
  -H "Authorization: Bearer $TOK")"
if [ "$HTTP_UPLOAD_DOWNLOAD" = "200" ] && grep -q "$UPLOAD_MARKER" "$UPLOAD_DOWNLOAD"; then
  ok "uploaded file is downloadable with unchanged content"
else
  bad "uploaded file download failed (HTTP $HTTP_UPLOAD_DOWNLOAD)"
fi

if curl -fsS -o "$WORKSPACE_JSON" "$BASE/api/agents/$AGID/workspace" \
  -H "Authorization: Bearer $TOK" 2>/dev/null && \
  python3 - "$WORKSPACE_JSON" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as fh:
    summary = json.load(fh)
assert summary["userFileBytes"] > 0
assert summary["orgFileBytes"] > 0
assert summary["maxFileBytes"] > 0
assert summary["userFileBytes"] <= summary["userFileLimitBytes"]
assert summary["orgFileBytes"] <= summary["orgFileLimitBytes"]
PY
then
  ok "workspace quota usage reflects uploaded content"
else
  bad "workspace quota summary is invalid"
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

if curl -fsS -o "$SESSION_JSON" "$BASE/api/agents/$AGID/chat/session" \
  -H "Authorization: Bearer $TOK" 2>/dev/null; then
  SESSION_KEY="$(cat "$SESSION_JSON" | json_get sessionKey)"
else
  SESSION_KEY=""
fi
if [ -n "$SESSION_KEY" ] && curl -fsS -o "$TURN_WINDOW_JSON" \
  "$BASE/api/agents/$AGID/sessions/$SESSION_KEY/turns/window?limit=1" \
  -H "Authorization: Bearer $TOK" 2>/dev/null; then
  BEFORE_SEQ="$(python3 - "$TURN_WINDOW_JSON" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as fh:
    page = json.load(fh)
assert len(page["items"]) == 1
assert page["hasMore"] is True
cursor = page["nextBeforeSeq"]
assert isinstance(cursor, int)
print(cursor)
PY
)"
  WINDOW_RC=$?
else
  BEFORE_SEQ=""
  WINDOW_RC=1
fi
if [ "$WINDOW_RC" -eq 0 ] && [ -n "$BEFORE_SEQ" ] && \
  curl -fsS -o "$OLDER_TURNS_JSON" \
  "$BASE/api/agents/$AGID/sessions/$SESSION_KEY/turns/window?limit=1&beforeSeq=$BEFORE_SEQ" \
  -H "Authorization: Bearer $TOK" 2>/dev/null && \
  BEFORE_SEQ="$BEFORE_SEQ" python3 - "$OLDER_TURNS_JSON" <<'PY'
import json
import os
import sys

with open(sys.argv[1], encoding="utf-8") as fh:
    page = json.load(fh)
assert len(page["items"]) == 1
assert page["items"][0]["seq"] < int(os.environ["BEFORE_SEQ"])
PY
then
  ok "long-session window loads newest turns and pages backwards"
else
  bad "long-session backwards window validation failed"
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

wait_backend_release || true

echo ""
echo "=== RESULT: PASS=$PASS FAIL=$FAIL ==="
[ "$FAIL" -eq 0 ] && exit 0 || exit 1
