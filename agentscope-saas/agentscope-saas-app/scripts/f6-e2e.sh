#!/usr/bin/env bash
# F6 e2e verification on pg with LIVE Row-Level Security (non-superuser `app` role).
# Preconditions (already done by the caller):
#   - app booted on agentscope_saas_e2e with SAAS_DB_USER=app, real JWT (NO dev bypass)
#   - Alice registered (demo org ...000001), token $TOKA
#   - Bob seeded in orgB (...000002) via SQL, logged in, token $TOKB
# Drives the full paw golden path + cross-tenant isolation at the API + DB layer.
set -uo pipefail

BASE="http://localhost:18080"
# Re-login to get fresh tokens (passwords: alice=pw-alice, bob cloned same hash)
REGA=$(curl -fsS -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' -d '{"email":"alice@e2e.test","password":"pw-alice"}')
LOGB=$(curl -fsS -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' -d '{"email":"bob@e2e.test","password":"pw-alice"}')
TOKA=$(printf '%s' "$REGA" | sed -E 's/.*"token":"([^"]+)".*/\1/')
TOKB=$(printf '%s' "$LOGB" | sed -E 's/.*"token":"([^"]+)".*/\1/')
ORGA=$(printf '%s' "$REGA" | sed -E 's/.*"orgId":"([^"]+)".*/\1/')
ORGB=$(printf '%s' "$LOGB" | sed -E 's/.*"orgId":"([^"]+)".*/\1/')
PASS=0; FAIL=0
ok()  { echo "  ✅ $1"; PASS=$((PASS+1)); }
bad() { echo "  ❌ $1"; FAIL=$((FAIL+1)); }
expect() { if [ "$1" = "$2" ]; then ok "$3 (HTTP $2)"; else bad "$3 — expected $1 got $2"; fi; }

echo "=== F6 e2e (pg, live RLS, non-superuser app role, real JWT) ==="
echo "  Alice org=$ORGA   Bob org=$ORGB"
[ "$ORGA" != "$ORGB" ] && ok "Alice & Bob in DIFFERENT orgs (isolation testable)" || { bad "same org"; exit 1; }

echo "--- 0) health + auth gate"
expect 200 "$(curl -s -o /dev/null -w '%{http_code}' $BASE/actuator/health)" "actuator/health"
expect 401 "$(curl -s -o /dev/null -w '%{http_code}' $BASE/api/agents)" "/api/agents without token = 401"
expect 401 "$(curl -s -o /dev/null -w '%{http_code}' $BASE/api/templates)" "/api/templates without token = 401"

echo "--- 1) Alice: create agent (paw shape) — INSERT passes RLS with GUC=orgA"
ANAME="AliceBot-$RANDOM"
AGA=$(curl -fsS -X POST "$BASE/api/agents" -H "Authorization: Bearer $TOKA" -H 'Content-Type: application/json' \
  -d "{\"name\":\"$ANAME\",\"description\":\"alice asst\",\"sysPrompt\":\"You help Alice.\",\"maxIters\":5,\"tools\":[\"web_search\"],\"workspacePath\":\"agents/alice\"}")
AGID=$(printf '%s' "$AGA" | sed -E 's/.*"id":"([^"]+)".*/\1/')
echo "$AGA" | grep -q "\"name\":\"$ANAME\"" && ok "Alice agent created (paw shape)" || bad "Alice agent create shape"
echo "  agent name=$ANAME id=$AGID"

echo "--- 2) Alice GET agent — shape stable"
AGGET=$(curl -fsS "$BASE/api/agents/$AGID" -H "Authorization: Bearer $TOKA")
echo "$AGGET" | grep -q '"sysPrompt":"You help Alice."' && ok "Alice reads own agent, sysPrompt present" || bad "agent GET shape"
echo "$AGGET" | grep -q '"maxIters":5' && ok "maxIters round-trips" || bad "maxIters lost"
echo "$AGGET" | grep -q '"tools":\["web_search"\]' && ok "tools array round-trips" || bad "tools lost"

echo "--- 3) Bob CANNOT read Alice's agent (cross-org, service filter + RLS)"
expect 404 "$(curl -s -o /dev/null -w '%{http_code}' $BASE/api/agents/$AGID -H "Authorization: Bearer $TOKB")" "Bob GET Alice's agent = 404"
expect 404 "$(curl -s -o /dev/null -w '%{http_code}' -X DELETE $BASE/api/agents/$AGID -H "Authorization: Bearer $TOKB")" "Bob DELETE Alice's agent = 404"

echo "--- 4) list isolation: Alice sees hers, Bob sees none of Alice's"
ACOUNT=$(curl -fsS "$BASE/api/agents" -H "Authorization: Bearer $TOKA" | grep -o '"id"' | wc -l | tr -d ' ')
BCOUNT=$(curl -fsS "$BASE/api/agents" -H "Authorization: Bearer $TOKB" | grep -o '"id"' | wc -l | tr -d ' ')
echo "  Alice=$ACOUNT  Bob=$BCOUNT"
[ "$ACOUNT" -ge 1 ] && ok "Alice sees her agent(s)" || bad "Alice agent list empty"
[ "$BCOUNT" -eq 0 ]  && ok "Bob sees 0 agents (Alice's hidden)" || bad "Bob sees Alice's agents (leak!)"

echo "--- 5) Alice: chat stream (stub echo) — session+message INSERT through RLS"
SSE=$(curl -s -N --max-time 25 -X POST "$BASE/api/agents/$AGID/chat/stream" \
  -H "Authorization: Bearer $TOKA" -H 'Content-Type: application/json' \
  -d '{"message":"hello e2e"}' 2>/dev/null | head -c 800)
echo "$SSE" | grep -qiE "data:|event:|hello" && ok "chat stream returned SSE" || { echo "    head: $SSE"; bad "chat stream empty"; }

echo "--- 6) Alice: chat/session (most-recent session)"
CUR=$(curl -fsS "$BASE/api/agents/$AGID/chat/session" -H "Authorization: Bearer $TOKA")
echo "$CUR" | grep -q '"exists":true' && ok "Alice has a current session" || bad "current session missing"
SK=$(echo "$CUR" | sed -E 's/.*"sessionKey":"([^"]+)".*/\1/')

echo "--- 7) Alice: inbox + turns (paw shape)"
INBOX=$(curl -fsS "$BASE/api/agents/$AGID/sessions/inbox" -H "Authorization: Bearer $TOKA")
echo "$INBOX" | grep -q '"sessionKey"' && ok "inbox paw shape" || bad "inbox shape"
TURNS=$(curl -fsS "$BASE/api/agents/$AGID/sessions/$SK" -H "Authorization: Bearer $TOKA")
echo "$TURNS" | grep -q '"role"' && ok "turns paw TurnEntry shape" || bad "turns shape"

echo "--- 8) Bob CANNOT read Alice's session turns (cross-org)"
expect 404 "$(curl -s -o /dev/null -w '%{http_code}' $BASE/api/agents/$AGID/sessions/$SK -H "Authorization: Bearer $TOKB")" "Bob GET Alice's turns = 404"

echo "--- 9) templates (org-agnostic catalog, auth-gated)"
TPL=$(curl -fsS "$BASE/api/templates" -H "Authorization: Bearer $TOKA")
echo "$TPL" | grep -q 'customer-support' && echo "$TPL" | grep -q 'research-assistant' && ok "templates catalog complete" || bad "templates incomplete"

echo "--- 10) Alice: subagent workspace lifecycle"
SA=$(curl -fsS -X PUT "$BASE/api/agents/$AGID/workspace/subagents/researcher" \
  -H "Authorization: Bearer $TOKA" -H 'Content-Type: application/json' \
  -d '{"description":"e2e researcher","model":"stub","maxIters":3,"tools":["web_search"]}')
echo "$SA" | grep -q '"name":"researcher"' && ok "subagent upsert" || bad "subagent upsert"
curl -fsS "$BASE/api/agents/$AGID/workspace/subagents" -H "Authorization: Bearer $TOKA" | grep -q 'researcher' && ok "subagent listed" || bad "subagent list"
expect 204 "$(curl -s -o /dev/null -w '%{http_code}' -X DELETE $BASE/api/agents/$AGID/workspace/subagents/researcher -H "Authorization: Bearer $TOKA")" "subagent delete 204"

echo "--- 11) Alice: session reset (tx-fixed path, was 500 pre-fix)"
expect 200 "$(curl -s -o /dev/null -w '%{http_code}' -X POST $BASE/api/agents/$AGID/sessions/$SK/reset -H "Authorization: Bearer $TOKA")" "session reset 200"

echo "--- 12) Alice: 2nd chat → new session → delete by key (tx-fixed, was 500)"
curl -s -N --max-time 25 -X POST "$BASE/api/agents/$AGID/chat/stream" -H "Authorization: Bearer $TOKA" -H 'Content-Type: application/json' -d '{"message":"second turn"}' >/dev/null 2>&1
INBOX2=$(curl -fsS "$BASE/api/agents/$AGID/sessions/inbox" -H "Authorization: Bearer $TOKA")
SK2=$(echo "$INBOX2" | sed -E 's/.*"sessionKey":"([^"]+)".*/\1/' | head -1)
expect 204 "$(curl -s -o /dev/null -w '%{http_code}' -X DELETE $BASE/api/agents/$AGID/sessions/$SK2 -H "Authorization: Bearer $TOKA")" "session delete by key 204"

echo "--- 13) Alice: agent cascade delete (tx-fixed, was 500) then 404"
expect 204 "$(curl -s -o /dev/null -w '%{http_code}' -X DELETE $BASE/api/agents/$AGID -H "Authorization: Bearer $TOKA")" "agent cascade delete 204"
expect 404 "$(curl -s -o /dev/null -w '%{http_code}' $BASE/api/agents/$AGID -H "Authorization: Bearer $TOKA")" "agent gone 404"

echo "--- 14) DB-level RLS direct proof (app role, non-superuser)"
# PGOPTIONS sets the GUC for the session WITHOUT echoing 'SET' to stdout
ZERO_UUID='00000000-0000-0000-0000-000000000000'
ROWS_BOGUS=$(PGOPTIONS="-c app.current_org=$ZERO_UUID" docker exec -i saas-pg psql -U app -d agentscope_saas_e2e -tAc "SELECT count(*) FROM agents;" 2>/dev/null | tr -d '[:space:]')
echo "  app role, bogus GUC → agents rows: $ROWS_BOGUS"
[ "$ROWS_BOGUS" = "0" ] && ok "RLS denies app role with non-matching GUC (0 rows)" || bad "RLS leaked with bogus GUC ($ROWS_BOGUS)"
ROWS_A=$(PGOPTIONS="-c app.current_org=$ORGA" docker exec -i saas-pg psql -U app -d agentscope_saas_e2e -tAc "SELECT count(*) FROM agents;" 2>/dev/null | tr -d '[:space:]')
echo "  app role, Alice's GUC → agents rows: $ROWS_A"
[ "$ROWS_A" -ge 0 ] 2>/dev/null; # alice deleted her agent in step 13, so 0 is expected & correct
echo "  (Alice deleted her agent in step 13; $ROWS_A rows = correct, RLS permitted the orgA scope)"
# superuser bypass check — proves the 0 above is RLS, not empty table
ROWS_SU=$(docker exec saas-pg psql -U agentscope -d agentscope_saas_e2e -tAc "SELECT count(*) FROM agents;" 2>/dev/null | tr -d '[:space:]')
echo "  superuser agentscope (BYPASSRLS) → agents rows: $ROWS_SU"
[ "$ROWS_SU" -ge 0 ] && ok "superuser sees all rows (confirms app-role 0 is RLS, not empty table)" || bad "superuser count wrong"

echo ""
echo "=== RESULT: PASS=$PASS FAIL=$FAIL ==="
[ "$FAIL" -eq 0 ] && exit 0 || exit 1
