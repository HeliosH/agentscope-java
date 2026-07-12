# AgentScope Enterprise SaaS Platform

A multi-tenant, enterprise AI assistant platform built on **agentscope-java** (`HarnessAgent`),
implementing Phase 1 of the technical plan in
[`docs/enterprise-platform-java/`](../../../docs/enterprise-platform-java/).

This module delivers a **runnable, verifiable foundation**:

- **Multi-tenancy** — `TenantContext` (org / user / role / tier / quotas) injected into the agent
  `RuntimeContext`, with a SaaS middleware chain (tenant → rate-limit → usage metering) layered on
  top of the framework's built-in sandbox-lifecycle / permission / trace middlewares.
- **Authentication** — local JWT login plus enterprise SSO readiness (OIDC/SAML via
  `issuer-uri` / `jwk-set-uri`).
- **AG-UI streaming chat** — `POST /api/chat/stream` runs the agent via
  `streamEvents(msgs, runtimeContext)` and streams AG-UI protocol events over SSE, wire-compatible
  with the frontend plan in `11-frontend-migration.md`.
- **Persistence** — PostgreSQL schema via Flyway (org / user / tier_policies / agents /
  chat_sessions / usage_records / audit_logs), all tenant-scoped by `org_id`.
- **Long sessions** — PostgreSQL message sequence/cursor history plus bounded model-context
  compaction; the web console restores the latest window and loads older turns on demand.
- **Files** — PostgreSQL metadata and immutable versions, MinIO bytes, per-user/per-org quotas,
  bounded multipart staging, optional internal ClamAV scanning, and retryable retention cleanup.
- **Session state** — Redis (Valkey) backed `RedisAgentStateStore` in production; in-memory locally.
- **Sandbox runtime** — switchable Docker, OpenSandbox, CubeSandbox, and E2B backends with
  per-user isolation, durable snapshots, TTL eviction, and backend-release reconciliation.

## Quick start — zero-dependency smoke test

Runs with an embedded H2 DB, in-memory state, and a stub echo model. No PostgreSQL / Redis / model
needed.

```bash
# from the repo root
mvn -pl agentscope-saas/agentscope-saas-app -am spring-boot:run \
  -Dspring-boot.run.profiles=local
```

Then:

```bash
# 1) Log in (demo user seeded by Flyway: alice@demo.local / password)
TOKEN=$(curl -s -X POST localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"alice@demo.local","password":"password"}' | sed -E 's/.*"token":"([^"]+)".*/\1/')

# 2) Who am I (tenant claims)
curl -s localhost:8080/api/auth/me -H "Authorization: Bearer $TOKEN"

# 3) Create a private agent
AGENT_ID=$(curl -s -X POST localhost:8080/api/agents \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"Local assistant"}' | sed -E 's/.*"id":"([^"]+)".*/\1/')

# 4) Stream an agent-scoped chat over AG-UI SSE
curl -N -X POST "localhost:8080/api/agents/$AGENT_ID/chat/stream" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"message":"hello"}'
```

You should see a sequence of AG-UI events: `RUN_STARTED`, `TEXT_MESSAGE_START`, several
`TEXT_MESSAGE_CONTENT` deltas, `TEXT_MESSAGE_END`, `RUN_FINISHED`.

## Connecting real middleware (production profile)

The default profile expects PostgreSQL, Valkey/Redis, and an OpenAI-compatible model gateway. Supply
addresses via environment variables, then run without the `local` profile:

```bash
export SAAS_DB_URL=jdbc:postgresql://<pg-host>:5432/agentscope_saas
export SAAS_DB_USER=...                 # PostgreSQL user
export SAAS_DB_PASSWORD=...
export SAAS_REDIS_URI=redis://<valkey-host>:6379
export SAAS_MODEL_TYPE=gateway          # OpenAI-compatible internal gateway
export SAAS_MODEL_BASE_URL=https://<gateway-host>/v1
export SAAS_MODEL_API_KEY=...
export SAAS_MODEL_NAME=qwen-max
export SAAS_JWT_SECRET=<32+ char secret>
# Optional enterprise SSO (validate IdP-issued tokens):
# export SAAS_OIDC_ISSUER_URI=https://<idp>/realms/<realm>

mvn -pl agentscope-saas/agentscope-saas-app -am spring-boot:run
```

## Building with the console UI

The React console under `frontend/` is built into the app's static resources with the `frontend`
Maven profile (requires Node, downloaded automatically):

```bash
mvn -pl agentscope-saas/agentscope-saas-app -am package -Pfrontend
```

Then open `http://localhost:8080/` after starting the app.

## Endpoints

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| POST | `/api/auth/login` | public | Local login → JWT |
| GET  | `/api/auth/me` | bearer | Current tenant claims |
| GET  | `/api/agents` | bearer | List org-scoped agents |
| POST | `/api/agents` | bearer | Create an agent |
| POST | `/api/agents/{agentId}/chat/stream` | bearer | Agent-scoped AG-UI SSE chat |
| GET  | `/api/agents/{agentId}/sessions/inbox` | bearer | User session inbox |
| GET  | `/api/agents/{agentId}/sessions/{sessionKey}/turns/window` | bearer | Latest history window / backward pagination |
| POST | `/api/agents/{agentId}/workspace/file/upload` | bearer | Multipart workspace upload |
| GET  | `/actuator/health` | public | Health check |

## Production file and conversation controls

The main controls are environment driven. `SAAS_AGENT_COMPACTION_*` tunes context compaction;
`SAAS_FILE_STORE_MAX_FILE_BYTES`, `SAAS_FILE_STORE_MAX_USER_BYTES`, and
`SAAS_FILE_STORE_MAX_ORG_BYTES` set storage limits. Enable the internally deployed malware scanner
with `SAAS_FILE_STORE_ANTIVIRUS_ENABLED=true` and its host/port settings. Deleted files are retained
for `SAAS_FILE_STORE_DELETED_RETENTION_DAYS` before the retryable GC job removes unreferenced
objects.
