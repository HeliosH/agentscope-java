#!/usr/bin/env bash
# OpenSandbox runtime lifecycle gate:
#   create sandbox -> wait Running -> execute command through execd proxy -> delete sandbox.
#
# Preconditions:
#   - OpenSandbox server is already running.
#   - For local Docker runtime, start OpenSandbox server separately with a reviewed security posture.
#
# Useful environment:
#   OPENSANDBOX_API_BASE_URL=http://localhost:18081/v1
#   OPENSANDBOX_API_KEY=...
#   OPENSANDBOX_IMAGE=ubuntu:latest
#   OPENSANDBOX_COMMAND='mkdir -p /workspace && printf "%s\n" opensandbox-smoke-ok > /workspace/report.txt && cat /workspace/report.txt'
set -uo pipefail

API_BASE_URL="${OPENSANDBOX_API_BASE_URL:-http://localhost:18081/v1}"
API_KEY="${OPENSANDBOX_API_KEY:-}"
IMAGE="${OPENSANDBOX_IMAGE:-ubuntu:latest}"
CPU_LIMIT="${OPENSANDBOX_CPU_LIMIT:-1}"
MEMORY_LIMIT="${OPENSANDBOX_MEMORY_LIMIT:-1Gi}"
SANDBOX_TIMEOUT="${OPENSANDBOX_SANDBOX_TIMEOUT:-300}"
WAIT_TIMEOUT="${OPENSANDBOX_WAIT_TIMEOUT:-120}"
EXECD_PORT="${OPENSANDBOX_EXECD_PORT:-44772}"
WORKSPACE_ROOT="${OPENSANDBOX_WORKSPACE_ROOT:-/workspace}"
COMMAND_CWD="${OPENSANDBOX_COMMAND_CWD:-/}"
MARKER="${OPENSANDBOX_MARKER:-opensandbox-smoke-ok}"
COMMAND="${OPENSANDBOX_COMMAND:-mkdir -p $WORKSPACE_ROOT && printf '%s\n' $MARKER > $WORKSPACE_ROOT/report.txt && cat $WORKSPACE_ROOT/report.txt}"
COMMAND_TIMEOUT="${OPENSANDBOX_COMMAND_TIMEOUT:-60}"
TMPDIR="${TMPDIR:-/tmp}"
RUN_ID="$(date +%s)-$$"
CREATE_JSON="$TMPDIR/opensandbox-create-$RUN_ID.json"
STATUS_JSON="$TMPDIR/opensandbox-status-$RUN_ID.json"
EXEC_STREAM="$TMPDIR/opensandbox-exec-$RUN_ID.stream"

PASS=0
FAIL=0
SANDBOX_ID=""

ok() {
  echo "  OK  $1"
  PASS=$((PASS + 1))
}

bad() {
  echo "  BAD $1"
  FAIL=$((FAIL + 1))
}

api_url() {
  printf '%s%s' "${API_BASE_URL%/}" "$1"
}

curl_common() {
  if [ -n "$API_KEY" ]; then
    curl -fsS -H "OPEN-SANDBOX-API-KEY: $API_KEY" "$@"
  else
    curl -fsS "$@"
  fi
}

json_get() {
  python3 -c '
import json, sys
node = json.load(sys.stdin)
for part in sys.argv[1].split("."):
    if not part:
        continue
    node = node.get(part, "") if isinstance(node, dict) else ""
print(node if node is not None else "")
' "$1"
}

cleanup() {
  if [ -n "$SANDBOX_ID" ]; then
    curl_common -X DELETE "$(api_url "/sandboxes/$SANDBOX_ID")" >/dev/null 2>&1 || true
  fi
  rm -f "$CREATE_JSON" "$STATUS_JSON" "$EXEC_STREAM"
}
trap cleanup EXIT

create_body() {
  IMAGE="$IMAGE" CPU_LIMIT="$CPU_LIMIT" MEMORY_LIMIT="$MEMORY_LIMIT" \
    SANDBOX_TIMEOUT="$SANDBOX_TIMEOUT" python3 - <<'PY'
import json
import os

print(json.dumps({
    "image": {"uri": os.environ["IMAGE"]},
    "entrypoint": ["tail", "-f", "/dev/null"],
    "timeout": int(os.environ["SANDBOX_TIMEOUT"]),
    "resourceLimits": {
        "cpu": os.environ["CPU_LIMIT"],
        "memory": os.environ["MEMORY_LIMIT"],
    },
    "metadata": {
        "agentscope.smoke": "opensandbox-runtime-lifecycle",
    },
}))
PY
}

exec_body() {
  COMMAND="$COMMAND" COMMAND_CWD="$COMMAND_CWD" COMMAND_TIMEOUT="$COMMAND_TIMEOUT" \
    python3 - <<'PY'
import json
import os

print(json.dumps({
    "command": os.environ["COMMAND"],
    "cwd": os.environ["COMMAND_CWD"],
    "background": False,
    "timeout": int(os.environ["COMMAND_TIMEOUT"]) * 1000,
}))
PY
}

parse_exec_stream() {
  python3 - "$EXEC_STREAM" "$MARKER" <<'PY'
import json
import sys

path, marker = sys.argv[1], sys.argv[2]
stdout = []
stderr = []
complete = False
error = None

def parse_frame(raw):
    raw = raw.strip()
    if not raw:
        return None
    if raw.startswith("data:"):
        payload = "\n".join(
            line[len("data:"):].strip()
            for line in raw.splitlines()
            if line.startswith("data:")
        )
    else:
        payload = raw
    if not payload.startswith("{"):
        return None
    return json.loads(payload)

with open(path, encoding="utf-8") as fh:
    frame = []
    for line in fh:
        if line.strip():
            frame.append(line.rstrip("\n"))
            continue
        event = parse_frame("\n".join(frame))
        frame = []
        if event is None:
            continue
        typ = event.get("type")
        if typ == "stdout":
            stdout.append(event.get("text", ""))
        elif typ == "stderr":
            stderr.append(event.get("text", ""))
        elif typ == "execution_complete":
            complete = True
        elif typ == "error":
            error = event.get("error") or event
    event = parse_frame("\n".join(frame))
    if event is not None:
        typ = event.get("type")
        if typ == "stdout":
            stdout.append(event.get("text", ""))
        elif typ == "stderr":
            stderr.append(event.get("text", ""))
        elif typ == "execution_complete":
            complete = True
        elif typ == "error":
            error = event.get("error") or event

out = "".join(stdout)
err = "".join(stderr)
if out:
    print(out, end="")
if err:
    print(err, end="", file=sys.stderr)
if error is not None:
    print(f"execd error: {json.dumps(error, ensure_ascii=False)}", file=sys.stderr)
    sys.exit(2)
if not complete:
    print("execd stream ended without execution_complete", file=sys.stderr)
    sys.exit(3)
if marker and marker not in out:
    print(f"expected marker not found: {marker}", file=sys.stderr)
    sys.exit(4)
PY
}

echo "=== OpenSandbox runtime lifecycle gate ==="
echo "  api_base_url=$API_BASE_URL"
echo "  image=$IMAGE"
echo "  workspace=$WORKSPACE_ROOT"
echo "  command_cwd=$COMMAND_CWD"

if ! create_body | curl_common -X POST "$(api_url /sandboxes)" \
  -H 'Content-Type: application/json' --data-binary @- > "$CREATE_JSON"; then
  bad "create sandbox request failed"
  exit 1
fi

SANDBOX_ID="$(json_get id < "$CREATE_JSON")"
if [ -n "$SANDBOX_ID" ]; then
  ok "sandbox created id=$SANDBOX_ID"
else
  bad "create response missing id"
  sed -n '1,20p' "$CREATE_JSON"
  exit 1
fi

deadline=$((SECONDS + WAIT_TIMEOUT))
state=""
while [ "$SECONDS" -lt "$deadline" ]; do
  if curl_common "$(api_url "/sandboxes/$SANDBOX_ID")" > "$STATUS_JSON"; then
    state="$(json_get status.state < "$STATUS_JSON")"
    if [ "$state" = "Running" ] || [ "$state" = "running" ]; then
      ok "sandbox reached Running"
      break
    fi
    if [ "$state" = "Failed" ] || [ "$state" = "failed" ] || [ "$state" = "Terminated" ] || [ "$state" = "terminated" ]; then
      bad "sandbox entered terminal state=$state"
      sed -n '1,40p' "$STATUS_JSON"
      exit 1
    fi
  fi
  sleep 1
done

if [ "$state" != "Running" ] && [ "$state" != "running" ]; then
  bad "timed out waiting for Running, last_state=$state"
  [ -s "$STATUS_JSON" ] && sed -n '1,40p' "$STATUS_JSON"
  exit 1
fi

if exec_body | curl_common -X POST "$(api_url "/sandboxes/$SANDBOX_ID/proxy/$EXECD_PORT/command")" \
  -H 'Content-Type: application/json' --data-binary @- > "$EXEC_STREAM"; then
  if parse_exec_stream; then
    ok "execd command returned expected marker"
  else
    bad "execd command stream parse failed"
    exit 1
  fi
else
  bad "execd command request failed"
  [ -s "$EXEC_STREAM" ] && sed -n '1,80p' "$EXEC_STREAM"
  exit 1
fi

if curl_common -X DELETE "$(api_url "/sandboxes/$SANDBOX_ID")" >/dev/null; then
  ok "sandbox deleted"
  SANDBOX_ID=""
else
  bad "sandbox delete failed"
fi

echo "=== summary: pass=$PASS fail=$FAIL ==="
[ "$FAIL" -eq 0 ]
