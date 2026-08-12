#!/usr/bin/env bash
# Start the AgentScope SaaS app against company dev middleware (company-dev profile).
#
# Prereqs:
#   1. scripts/company-dev-env.sh exists and has no CHANGE_ME placeholders.
#   2. psql on PATH is recommended: the script then auto-creates the database
#      (SAAS_DB_NAME) and the `app`/`agentscope` roles that Flyway V10/V12
#      reference. Without psql, create both manually once via pgAdmin/DBeaver
#      (see the WARN message the script prints).
#   3. Node is NOT required on the host - the frontend Maven profile downloads
#      Node itself (needs internet on first build).
#
# Usage:
#   ./start-company-dev.sh             # build jar (if missing) and start
#   REBUILD=1 ./start-company-dev.sh   # force rebuild (after code/frontend changes)
#   NO_FRONTEND=1 ./start-company-dev.sh  # skip React console build (API-only)
#   SERVER_PORT=8081 ./start-company-dev.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
APP_MODULE="agentscope-saas/agentscope-saas-app"
ENV_FILE="$SCRIPT_DIR/company-dev-env.sh"
PID_FILE="$SCRIPT_DIR/company-dev.pid"
LOG_FILE="$SCRIPT_DIR/company-dev.log"
PROVISION_SQL="$SCRIPT_DIR/company-dev-provision-roles.sql"

require_command() {
  command -v "$1" >/dev/null 2>&1 || { echo "Required command not found: $1" >&2; exit 1; }
}
require_command mvn
require_command curl

# --- 1. Load env ---
if [ ! -f "$ENV_FILE" ]; then
  echo "Missing $ENV_FILE." >&2
  echo "Create it from the template:  cp $SCRIPT_DIR/company-dev-env.sh.example $ENV_FILE" >&2
  echo "Then fill the CHANGE_ME placeholders (DB name, model endpoint)." >&2
  exit 1
fi
# shellcheck disable=SC1090
source "$ENV_FILE"

if grep -Eq '^[[:space:]]*export [^#]*CHANGE_ME' "$ENV_FILE"; then
  echo "ERROR: $ENV_FILE still has CHANGE_ME placeholders. Fill them in first:" >&2
  grep -nE '^[[:space:]]*export [^#]*CHANGE_ME' "$ENV_FILE" >&2
  exit 1
fi

PORT="${SERVER_PORT:-8080}"
HEALTH_URL="http://localhost:${PORT}/actuator/health"

# --- 2. Already running? ---
if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
  echo "App already running (pid $(cat "$PID_FILE")). Stop it first: $SCRIPT_DIR/stop-company-dev.sh"
  exit 0
fi

# --- 3. Ensure the database exists + provision Postgres roles (app, agentscope) ---
# comac is a superuser, so it can CREATE DATABASE. Flyway V10/V12 reference the
# `app`/`agentscope` roles, which must exist before boot; both are idempotent.
if command -v psql >/dev/null 2>&1; then
  PSQL_COMMON=(psql -h 192.168.6.15 -p 5432 -U "$SAAS_DB_USER")
  # 3a. Create the database if it does not exist (connect to the maintenance `postgres` db).
  if PGPASSWORD="$SAAS_DB_PASSWORD" "${PSQL_COMMON[@]}" -d postgres -tAc \
      "SELECT 1 FROM pg_database WHERE datname = '$SAAS_DB_NAME'" | grep -q 1; then
    echo "Ready: database $SAAS_DB_NAME"
  else
    echo "Creating database $SAAS_DB_NAME (owner $SAAS_DB_USER)..."
    if ! PGPASSWORD="$SAAS_DB_PASSWORD" "${PSQL_COMMON[@]}" -d postgres \
          -c "CREATE DATABASE \"$SAAS_DB_NAME\""; then
      echo "ERROR: CREATE DATABASE failed (see above). Verify credentials / network," >&2
      echo "       or create it manually:  CREATE DATABASE \"$SAAS_DB_NAME\";" >&2
      exit 1
    fi
    echo "Ready: database $SAAS_DB_NAME"
  fi
  # 3b. Provision the app/agentscope roles (idempotent) on the target database.
  echo "Provisioning Postgres roles (app, agentscope)..."
  if ! PGPASSWORD="$SAAS_DB_PASSWORD" "${PSQL_COMMON[@]}" -d "$SAAS_DB_NAME" \
        -f "$PROVISION_SQL" >/dev/null; then
    echo "ERROR: role provisioning failed (see above). Verify DB name / network," >&2
    echo "       or run $PROVISION_SQL manually via pgAdmin/DBeaver." >&2
    exit 1
  fi
  echo "Ready: Postgres roles (app, agentscope)"
else
  echo "WARN: psql not found. Before first boot, run these once against the company Postgres" >&2
  echo "      as the comac superuser (via pgAdmin / DBeaver):" >&2
  echo "    1) CREATE DATABASE \"$SAAS_DB_NAME\";" >&2
  echo "    2) $PROVISION_SQL   (creates the app/agentscope roles Flyway V10/V12 reference)" >&2
fi

# --- 4. Build the fat jar (with React console) if missing or REBUILD=1 ---
JAR="$(ls "$REPO_ROOT/$APP_MODULE"/target/agentscope-saas-app-*.jar 2>/dev/null | grep -v '\-sources\.' | head -1 || true)"
if [ -z "$JAR" ] || [ "${REBUILD:-0}" = "1" ]; then
  MVN_PROFILE_ARGS=()
  if [ "${NO_FRONTEND:-0}" != "1" ]; then
    echo "Building app jar with React console (-Pfrontend; first build downloads Node)..."
    MVN_PROFILE_ARGS+=(-Pfrontend)
  else
    echo "Building app jar (API-only, no frontend)..."
  fi
  cd "$REPO_ROOT"
  mvn -pl "$APP_MODULE" -am "${MVN_PROFILE_ARGS[@]}" -DskipTests clean package
  JAR="$(ls "$REPO_ROOT/$APP_MODULE"/target/agentscope-saas-app-*.jar | grep -v '\-sources\.' | head -1)"
fi
[ -n "$JAR" ] || { echo "Could not locate built jar." >&2; exit 1; }

# --- 5. Start ---
echo
echo "Starting AgentScope SaaS app (company-dev)"
echo "  jar:     $JAR"
echo "  url:     http://localhost:$PORT"
echo "  login:   alice@demo.local / password   (seeded by Flyway V2)"
echo "  model:   gateway -> $SAAS_MODEL_BASE_URL ($SAAS_MODEL_NAME)"
echo "  sandbox: cube (api http://192.168.48.149:3000, tpl tpl-525000c672df4d5fad363695)"
echo "  log:     $LOG_FILE"
echo
cd "$REPO_ROOT"
nohup java -jar "$JAR" > "$LOG_FILE" 2>&1 &
APP_PID=$!
echo "$APP_PID" > "$PID_FILE"
disown "$APP_PID" 2>/dev/null || true

# --- 6. Wait for health ---
deadline=$((SECONDS + 240))
while :; do
  if ! kill -0 "$APP_PID" 2>/dev/null; then
    echo "ERROR: app process exited during startup. Last 60 log lines:" >&2
    tail -60 "$LOG_FILE" >&2 || true
    rm -f "$PID_FILE"
    exit 1
  fi
  if curl -fsS --max-time 3 "$HEALTH_URL" >/dev/null 2>&1; then
    break
  fi
  if [ "$SECONDS" -ge "$deadline" ]; then
    echo "ERROR: timed out waiting for $HEALTH_URL. Last 60 log lines:" >&2
    tail -60 "$LOG_FILE" >&2 || true
    exit 1
  fi
  sleep 3
done
echo "App is ready: $HEALTH_URL  (pid $APP_PID)"
echo "Stop with: $SCRIPT_DIR/stop-company-dev.sh"
