#!/usr/bin/env bash
# Repeated E2B runtime lifecycle gate for release/resource-management hardening.
#
# Preconditions:
#   - App is already running on BASE=http://localhost:18080 by default.
#   - The app uses SAAS_SANDBOX_TYPE=e2b with scripted model and MinIO snapshots.
#   - CubeSandbox is intentionally not used here.
#
# Useful knobs:
#   E2B_STRESS_RUNS=3
#   E2B_STRESS_TIMEOUT=180
#   E2B_STRESS_EMAIL_PREFIX=e2b-stress
set -uo pipefail

BASE="${BASE:-http://localhost:18080}"
RUNS="${E2B_STRESS_RUNS:-3}"
TIMEOUT="${E2B_STRESS_TIMEOUT:-180}"
EMAIL_PREFIX="${E2B_STRESS_EMAIL_PREFIX:-e2b-stress}"
PASSWORD="${E2B_STRESS_PASSWORD:-pw-e2b-stress}"
MINIO_CONTAINER="${MINIO_CONTAINER:-saas-minio}"
MINIO_BUCKET="${MINIO_BUCKET:-agentscope-saas-e2b}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LIFECYCLE_SCRIPT="$SCRIPT_DIR/e2b-runtime-lifecycle.sh"

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

metric_url() {
  python3 - "$BASE" "$@" <<'PY'
import sys
from urllib.parse import urlencode

base = sys.argv[1].rstrip("/")
metric = sys.argv[2]
tags = sys.argv[3:]
query = urlencode([("tag", tag) for tag in tags])
url = f"{base}/actuator/metrics/{metric}"
if query:
    url = f"{url}?{query}"
print(url)
PY
}

metric_value() {
  local metric="$1"
  shift
  local url
  local body

  url="$(metric_url "$metric" "$@")"
  if ! body="$(curl -fsS "$url" 2>/dev/null)"; then
    return 1
  fi
  METRIC_BODY="$body" python3 - <<'PY'
import json
import os
import sys

try:
    data = json.loads(os.environ["METRIC_BODY"])
except Exception:
    sys.exit(1)

measurements = data.get("measurements") or []
for preferred in ("COUNT", "VALUE", "TOTAL", "MAX"):
    for item in measurements:
        if item.get("statistic") == preferred and item.get("value") is not None:
            print(item["value"])
            sys.exit(0)
sys.exit(1)
PY
}

metric_count_or_zero() {
  local value
  value="$(metric_value "$@" 2>/dev/null || true)"
  if [ -z "$value" ]; then
    printf '0'
  else
    python3 - "$value" <<'PY'
import sys
try:
    print(int(float(sys.argv[1])))
except Exception:
    print(0)
PY
  fi
}

check_metric_delta() {
  local event="$1"
  local before="$2"
  local after="$3"
  local min_delta="$4"
  local delta=$((after - before))
  if [ "$delta" -ge "$min_delta" ]; then
    ok "metric event=$event delta=$delta"
  else
    bad "metric event=$event expected delta >= $min_delta, got $delta"
  fi
}

if [ ! -x "$LIFECYCLE_SCRIPT" ]; then
  bad "missing executable lifecycle script: $LIFECYCLE_SCRIPT"
  exit 1
fi

case "$RUNS" in
  ''|*[!0-9]*)
    bad "E2B_STRESS_RUNS must be a positive integer"
    exit 1
    ;;
esac
if [ "$RUNS" -le 0 ]; then
  bad "E2B_STRESS_RUNS must be > 0"
  exit 1
fi

echo "=== E2B runtime stress gate ==="
echo "  base=$BASE"
echo "  runs=$RUNS"
echo "  cube_sandbox=not used"

HTTP_HEALTH="$(curl -s -o /dev/null -w '%{http_code}' "$BASE/actuator/health")"
if [ "$HTTP_HEALTH" = "200" ]; then
  ok "health endpoint is ready"
else
  bad "health endpoint returned HTTP $HTTP_HEALTH"
  exit 1
fi

REGISTERED_BEFORE="$(metric_count_or_zero saas.sandbox.lifecycle.events type:e2b event:registered)"
RELEASED_BEFORE="$(metric_count_or_zero saas.sandbox.lifecycle.events type:e2b event:released)"
TRACKING_RELEASE_FAILED_BEFORE="$(metric_count_or_zero saas.sandbox.lifecycle.events type:e2b event:tracking_release_failed)"
BACKEND_RELEASE_FAILED_BEFORE="$(metric_count_or_zero saas.sandbox.lifecycle.events type:e2b event:backend_release_failed)"
PERSIST_FAILED_BEFORE="$(metric_count_or_zero saas.sandbox.lifecycle.events type:e2b event:state_persist_failed)"
PROJECTION_FAILED_BEFORE="$(metric_count_or_zero saas.sandbox.lifecycle.events type:e2b event:workspace_projection_failed)"

i=1
while [ "$i" -le "$RUNS" ]; do
  email="$EMAIL_PREFIX-$i-$(date +%s)-$$@e2e.test"
  echo ""
  echo "--- run $i/$RUNS email=$email ---"
  if BASE="$BASE" \
    E2B_LIFECYCLE_EMAIL="$email" \
    E2B_LIFECYCLE_PASSWORD="$PASSWORD" \
    E2B_LIFECYCLE_TIMEOUT="$TIMEOUT" \
    MINIO_CONTAINER="$MINIO_CONTAINER" \
    MINIO_BUCKET="$MINIO_BUCKET" \
    "$LIFECYCLE_SCRIPT"; then
    ok "lifecycle run $i passed"
  else
    bad "lifecycle run $i failed"
  fi
  i=$((i + 1))
done

REGISTERED_AFTER="$(metric_count_or_zero saas.sandbox.lifecycle.events type:e2b event:registered)"
RELEASED_AFTER="$(metric_count_or_zero saas.sandbox.lifecycle.events type:e2b event:released)"
TRACKING_RELEASE_FAILED_AFTER="$(metric_count_or_zero saas.sandbox.lifecycle.events type:e2b event:tracking_release_failed)"
BACKEND_RELEASE_FAILED_AFTER="$(metric_count_or_zero saas.sandbox.lifecycle.events type:e2b event:backend_release_failed)"
PERSIST_FAILED_AFTER="$(metric_count_or_zero saas.sandbox.lifecycle.events type:e2b event:state_persist_failed)"
PROJECTION_FAILED_AFTER="$(metric_count_or_zero saas.sandbox.lifecycle.events type:e2b event:workspace_projection_failed)"

echo ""
echo "--- metric deltas ---"
check_metric_delta "registered" "$REGISTERED_BEFORE" "$REGISTERED_AFTER" "$RUNS"
check_metric_delta "released" "$RELEASED_BEFORE" "$RELEASED_AFTER" "$RUNS"

if [ $((TRACKING_RELEASE_FAILED_AFTER - TRACKING_RELEASE_FAILED_BEFORE)) -eq 0 ]; then
  ok "no tracking release failures"
else
  bad "tracking release failures increased"
fi
if [ $((BACKEND_RELEASE_FAILED_AFTER - BACKEND_RELEASE_FAILED_BEFORE)) -eq 0 ]; then
  ok "no backend release failures"
else
  bad "backend release failures increased"
fi
if [ $((PERSIST_FAILED_AFTER - PERSIST_FAILED_BEFORE)) -eq 0 ]; then
  ok "no state persist failures"
else
  bad "state persist failures increased"
fi
if [ $((PROJECTION_FAILED_AFTER - PROJECTION_FAILED_BEFORE)) -eq 0 ]; then
  ok "no workspace projection failures"
else
  bad "workspace projection failures increased"
fi

EXPIRED_ACTIVE="$(metric_value saas.sandbox.pool.expired_active type:e2b 2>/dev/null || true)"
if [ -n "$EXPIRED_ACTIVE" ]; then
  echo "  INFO expired_active_gauge=$EXPIRED_ACTIVE"
else
  echo "  SKIP expired_active_gauge (inventory job has not published it yet)"
fi

echo ""
echo "=== RESULT: PASS=$PASS FAIL=$FAIL ==="
[ "$FAIL" -eq 0 ] && exit 0 || exit 1
