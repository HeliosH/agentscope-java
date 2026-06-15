#!/usr/bin/env bash
#
# Phase 1 local smoke test for the AgentScope Enterprise SaaS platform.
# Assumes the app is running with the `local` profile on :8080.
#
# Usage: ./smoke-test.sh
set -euo pipefail

BASE="${BASE:-http://localhost:8080}"

echo "==> 1) Health check"
curl -fsS "$BASE/actuator/health" && echo

echo "==> 2) Login (alice@demo.local / password)"
LOGIN=$(curl -fsS -X POST "$BASE/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"email":"alice@demo.local","password":"password"}')
echo "$LOGIN"
TOKEN=$(printf '%s' "$LOGIN" | sed -E 's/.*"token":"([^"]+)".*/\1/')
if [ -z "$TOKEN" ] || [ "$TOKEN" = "$LOGIN" ]; then
  echo "FAILED to extract token"; exit 1
fi

echo "==> 3) /api/auth/me (tenant claims)"
curl -fsS "$BASE/api/auth/me" -H "Authorization: Bearer $TOKEN" && echo

echo "==> 4) List agents (org-scoped, expect empty array initially)"
curl -fsS "$BASE/api/agents" -H "Authorization: Bearer $TOKEN" && echo

echo "==> 5) Create an agent"
curl -fsS -X POST "$BASE/api/agents" -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"name":"my-assistant"}' && echo

echo "==> 6) Stream a chat over AG-UI SSE"
curl -fsS -N -X POST "$BASE/api/chat/stream" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"agentId":"default","sessionId":"smoke-1","message":"hello there"}'
echo
echo "==> 7) List sessions"
curl -fsS "$BASE/api/sessions" -H "Authorization: Bearer $TOKEN" && echo

echo "==> 8) Unauthenticated chat should be rejected (401)"
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/chat/stream" \
  -H 'Content-Type: application/json' -d '{"message":"x"}')
echo "unauthenticated chat status: $code (expect 401)"

echo "==> Smoke test complete."
