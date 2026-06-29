#!/usr/bin/env bash
# E2B runtime lifecycle gate for the enterprise assistant.
#
# Preconditions:
#   - App is already running, usually on BASE=http://localhost:18080.
#   - The app is configured with:
#       SAAS_SANDBOX_ENABLED=true
#       SAAS_SANDBOX_TYPE=e2b
#       SAAS_SANDBOX_SNAPSHOT_BACKEND=minio
#       SAAS_MODEL_TYPE=scripted
#       SAAS_REDIS_ENABLED=true
#   - CubeSandbox is intentionally not used by this script.
#
# Optional local evidence:
#   MINIO_CONTAINER=saas-minio MINIO_BUCKET=agentscope-saas-e2b
# If Docker/MinIO are available, the script checks snapshot object growth.
set -uo pipefail

BASE="${BASE:-http://localhost:18080}"
EMAIL="${E2B_LIFECYCLE_EMAIL:-e2b-lifecycle-$(date +%s)@e2e.test}"
PASSWORD="${E2B_LIFECYCLE_PASSWORD:-pw-e2b-lifecycle}"
TIMEOUT="${E2B_LIFECYCLE_TIMEOUT:-180}"
TMPDIR="${TMPDIR:-/tmp}"
MINIO_CONTAINER="${MINIO_CONTAINER:-saas-minio}"
MINIO_BUCKET="${MINIO_BUCKET:-agentscope-saas-e2b}"
RUN_ID="$(date +%s)-$$"

PASS=0
FAIL=0
TOK=""
AGID=""
SNAPSHOT_OBJECT_COUNT_BEFORE=""

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

json_body() {
  python3 - "$@" <<'PY'
import json
import sys

print(json.dumps(dict(arg.split("=", 1) for arg in sys.argv[1:])))
PY
}

http_code() {
  local url="$1"
  local attempts="${2:-10}"
  local code=""
  local i=1

  while [ "$i" -le "$attempts" ]; do
    code="$(curl -s -o /dev/null -w '%{http_code}' "$url")"
    if [ "$code" != "000" ]; then
      printf '%s' "$code"
      return 0
    fi
    sleep 1
    i=$((i + 1))
  done
  printf '%s' "$code"
}

snapshot_object_count() {
  local listing

  if ! command -v docker >/dev/null 2>&1; then
    return 1
  fi
  if ! listing="$(docker exec "$MINIO_CONTAINER" sh -c \
    "ls -1 /data/$MINIO_BUCKET/snapshots 2>/dev/null" 2>/dev/null)"; then
    return 1
  fi
  printf '%s\n' "$listing" \
    | python3 -c 'import sys; print(sum(1 for line in sys.stdin if line.strip().endswith(".tar.gz")))'
}

cleanup() {
  if [ -n "$TOK" ] && [ -n "$AGID" ]; then
    curl -s -o /dev/null -X DELETE "$BASE/api/agents/$AGID" \
      -H "Authorization: Bearer $TOK" || true
  fi
  rm -f "$TMPDIR/e2b-lifecycle-"*-"$RUN_ID".*
}
trap cleanup EXIT

parse_confirm() {
  python3 - "$1" "$2" <<'PY'
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
    if ev.get("type") != "CUSTOM" or ev.get("name") != "require_user_confirm":
        continue
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
}

chat_execute() {
  local label="$1"
  local command="$2"
  local expected="$3"
  local sse1="$TMPDIR/e2b-lifecycle-$label-1-$RUN_ID.sse"
  local sse2="$TMPDIR/e2b-lifecycle-$label-2-$RUN_ID.sse"
  local confirm="$TMPDIR/e2b-lifecycle-$label-confirm-$RUN_ID.json"
  local body

  body="$(COMMAND="$command" python3 - <<'PY'
import json
import os

print(json.dumps({"message": os.environ["COMMAND"]}))
PY
)"

  curl -s -N --max-time "$TIMEOUT" -X POST "$BASE/api/agents/$AGID/chat/stream" \
    -H "Authorization: Bearer $TOK" -H 'Content-Type: application/json' \
    -d "$body" > "$sse1" 2>/dev/null

  if ! grep -q "data:" "$sse1"; then
    bad "$label returned no SSE"
    sed -n '1,20p' "$sse1"
    return 1
  fi

  if parse_confirm "$sse1" "$confirm"; then
    ok "$label requested HITL confirmation"
    curl -s -N --max-time "$TIMEOUT" -X POST "$BASE/api/agents/$AGID/chat/stream" \
      -H "Authorization: Bearer $TOK" -H 'Content-Type: application/json' \
      --data-binary "@$confirm" > "$sse2" 2>/dev/null
  else
    cp "$sse1" "$sse2"
    ok "$label completed without HITL confirmation"
  fi

  if grep -q "$expected" "$sse2"; then
    ok "$label returned expected marker"
  else
    bad "$label missing expected marker '$expected'"
    python3 - "$sse2" <<'PY'
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
    return 1
  fi
}

download_expect() {
  local path="$1"
  local expected="$2"
  local out="$TMPDIR/e2b-lifecycle-download-$RUN_ID.txt"
  local code

  code="$(curl -s -o "$out" -w '%{http_code}' \
    "$BASE/api/agents/$AGID/workspace/file/download?path=$path" \
    -H "Authorization: Bearer $TOK")"
  if [ "$code" = "200" ] && grep -q "$expected" "$out"; then
    ok "download $path contains $expected"
  else
    bad "download $path expected HTTP 200 with $expected, got HTTP $code"
    [ -s "$out" ] && sed -n '1,20p' "$out"
  fi
}

download_404() {
  local path="$1"
  local out="$TMPDIR/e2b-lifecycle-download-404-$RUN_ID.txt"
  local code

  code="$(curl -s -o "$out" -w '%{http_code}' \
    "$BASE/api/agents/$AGID/workspace/file/download?path=$path" \
    -H "Authorization: Bearer $TOK")"
  if [ "$code" = "404" ]; then
    ok "download $path returns 404 after sandbox delete/move"
  else
    bad "download $path expected HTTP 404, got HTTP $code"
    [ -s "$out" ] && sed -n '1,20p' "$out"
  fi
}

echo "=== E2B runtime lifecycle gate ==="
echo "  base=$BASE"
echo "  email=$EMAIL"
echo "  cube_sandbox=not used"

HTTP_HEALTH="$(http_code "$BASE/actuator/health")"
if [ "$HTTP_HEALTH" = "200" ]; then
  ok "health endpoint is ready"
else
  bad "health endpoint returned HTTP $HTTP_HEALTH"
  exit 1
fi

SNAPSHOT_OBJECT_COUNT_BEFORE="$(snapshot_object_count || true)"
if [ -n "$SNAPSHOT_OBJECT_COUNT_BEFORE" ]; then
  ok "MinIO snapshot object count before=$SNAPSHOT_OBJECT_COUNT_BEFORE"
else
  echo "  SKIP MinIO snapshot object count before (docker/minio unavailable)"
fi

REG_BODY="$(json_body email="$EMAIL" password="$PASSWORD" displayName="E2B Lifecycle")"
AUTH_JSON="$(curl -fsS -X POST "$BASE/api/auth/register" \
  -H 'Content-Type: application/json' -d "$REG_BODY" 2>/dev/null)"
TOK="$(printf '%s' "$AUTH_JSON" | json_get token)"
ORG="$(printf '%s' "$AUTH_JSON" | json_get orgId)"
if [ -n "$TOK" ]; then
  ok "user registered org=$ORG"
else
  bad "register failed: $AUTH_JSON"
  exit 1
fi

AGENT_BODY="$(python3 - <<'PY'
import json

print(json.dumps({
  "name": "E2BLifecycle",
  "description": "E2B runtime lifecycle gate",
  "sysPrompt": "Execute the requested shell command exactly and return the output.",
  "maxIters": 8,
  "tools": ["execute"],
  "workspacePath": "agents/e2b-lifecycle",
}))
PY
)"
AGENT_JSON="$(curl -fsS -X POST "$BASE/api/agents" \
  -H "Authorization: Bearer $TOK" -H 'Content-Type: application/json' \
  -d "$AGENT_BODY")"
AGID="$(printf '%s' "$AGENT_JSON" | json_get id)"
if [ -n "$AGID" ]; then
  ok "agent created id=$AGID"
else
  bad "agent create failed: $AGENT_JSON"
  exit 1
fi

OLD_MARKER="old-$RUN_ID"
KEEP_MARKER="keep-$RUN_ID"
MOVED_MARKER="moved-$RUN_ID"
NEW_MARKER="new-$RUN_ID"

CMD1="mkdir -p generated && printf '%s\n' '$OLD_MARKER' > generated/old.txt && printf '%s\n' '$KEEP_MARKER' > generated/keep.txt && cat generated/old.txt && cat generated/keep.txt"
chat_execute "chat1-create" "$CMD1" "$KEEP_MARKER" || exit 1
download_expect "generated/old.txt" "$OLD_MARKER"
download_expect "generated/keep.txt" "$KEEP_MARKER"

CMD2="test -f generated/old.txt && test -f generated/keep.txt && rm -f generated/old.txt && mv generated/keep.txt generated/moved.txt && printf '%s\n' '$MOVED_MARKER' >> generated/moved.txt && printf '%s\n' '$NEW_MARKER' > generated/new.txt && cat generated/moved.txt && cat generated/new.txt"
chat_execute "chat2-restore-mutate" "$CMD2" "$NEW_MARKER" || exit 1
download_404 "generated/old.txt"
download_expect "generated/moved.txt" "$MOVED_MARKER"
download_expect "generated/new.txt" "$NEW_MARKER"

SNAPSHOT_OBJECT_COUNT_AFTER="$(snapshot_object_count || true)"
if [ -n "$SNAPSHOT_OBJECT_COUNT_BEFORE" ] && [ -n "$SNAPSHOT_OBJECT_COUNT_AFTER" ]; then
  if [ "$SNAPSHOT_OBJECT_COUNT_AFTER" -gt "$SNAPSHOT_OBJECT_COUNT_BEFORE" ]; then
    ok "MinIO snapshot object count grew: $SNAPSHOT_OBJECT_COUNT_BEFORE -> $SNAPSHOT_OBJECT_COUNT_AFTER"
  else
    bad "MinIO snapshot object count did not grow: $SNAPSHOT_OBJECT_COUNT_BEFORE -> $SNAPSHOT_OBJECT_COUNT_AFTER"
  fi
else
  echo "  SKIP MinIO snapshot object count after (docker/minio unavailable)"
fi

HTTP_DELETE="$(curl -s -o /dev/null -w '%{http_code}' -X DELETE "$BASE/api/agents/$AGID" \
  -H "Authorization: Bearer $TOK")"
if [ "$HTTP_DELETE" = "204" ]; then
  ok "agent deleted"
  AGID=""
else
  bad "agent delete returned HTTP $HTTP_DELETE"
fi

echo ""
echo "=== RESULT: PASS=$PASS FAIL=$FAIL ==="
[ "$FAIL" -eq 0 ] && exit 0 || exit 1
