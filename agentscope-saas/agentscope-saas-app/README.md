# AgentScope Enterprise SaaS Platform (Phase 1 Foundation)

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
- **Session state** — Redis (Valkey) backed `RedisAgentStateStore` in production; in-memory locally.
- **Sandbox-ready** — `IsolationScope.USER` sandbox isolation can be enabled via
  `saas.sandbox.enabled=true` (Docker) in a later phase.

## Quick start — zero-dependency smoke test

Runs with an embedded H2 DB, in-memory state, and a stub echo model. No PostgreSQL / Redis / model
needed.

```bash
# from the repo root
mvn -pl agentscope-examples/agents/agentscope-saas -am spring-boot:run \
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

# 3) Stream a chat over AG-UI SSE
curl -N -X POST localhost:8080/api/chat/stream \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"agentId":"default","sessionId":"s1","message":"hello"}'
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

mvn -pl agentscope-examples/agents/agentscope-saas -am spring-boot:run
```

## Building with the console UI

The minimal AG-UI console (login + streaming chat) under `frontend/` is built into the app's static
resources with the `frontend` Maven profile (requires Node, downloaded automatically):

```bash
mvn -pl agentscope-examples/agents/agentscope-saas -am package -Pfrontend
```

Then open `http://localhost:8080/` after starting the app.

## Endpoints

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| POST | `/api/auth/login` | public | Local login → JWT |
| GET  | `/api/auth/me` | bearer | Current tenant claims |
| POST | `/api/chat/stream` | bearer | AG-UI SSE streaming chat |
| GET  | `/api/agents` | bearer | List org-scoped agents |
| POST | `/api/agents` | bearer | Create an agent |
| GET  | `/api/sessions` | bearer | List the user's chat sessions |
| GET  | `/actuator/health` | public | Health check |

## What's deferred to later phases

Channel adapters (DingTalk/Feishu/…), skill marketplace, Admin dashboard, pgvector memory, full
Row-Level Security, CubeSandbox, token-accurate metering, and the full console (frontend plan
F2–F5). See [`10-roadmap.md`](../../../docs/enterprise-platform-java/10-roadmap.md).
