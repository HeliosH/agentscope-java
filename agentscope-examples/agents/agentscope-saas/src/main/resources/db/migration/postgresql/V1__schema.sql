--
-- Copyright 2024-2026 the original author or authors.
--
-- Licensed under the Apache License, Version 2.0 (the "License");
-- you may not use this file except in compliance with the License.
-- You may obtain a copy of the License at
--
--      http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing, software
-- distributed under the License is distributed on an "AS IS" BASIS,
-- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
-- See the License for the specific language governing permissions and
-- limitations under the License.
--

-- Enterprise SaaS platform schema (Phase 1) — PostgreSQL.
-- All tenant-scoped tables carry org_id for isolation. Row-Level Security (RLS)
-- policies are deferred to a later phase; Phase 1 enforces isolation in the service layer.

CREATE TABLE orgs (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name       VARCHAR(255) NOT NULL,
    slug       VARCHAR(64) UNIQUE NOT NULL,
    settings   JSONB DEFAULT '{}',
    status     VARCHAR(20) DEFAULT 'active',
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE tier_policies (
    tier                VARCHAR(20) PRIMARY KEY,
    max_agents          INTEGER,
    max_sandboxes       INTEGER,
    monthly_token_quota BIGINT,
    storage_gb          INTEGER,
    idle_ttl_seconds    INTEGER
);

CREATE TABLE users (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id        UUID NOT NULL REFERENCES orgs(id),
    email         VARCHAR(255) UNIQUE NOT NULL,
    idp_subject   VARCHAR(255),
    display_name  VARCHAR(255),
    password_hash VARCHAR(200),
    role          VARCHAR(20) DEFAULT 'member',
    tier          VARCHAR(20) DEFAULT 'standard',
    created_at    TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX ix_users_org ON users(org_id);

CREATE TABLE agents (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id       UUID NOT NULL,
    user_id      UUID NOT NULL REFERENCES users(id),
    name         VARCHAR(255) NOT NULL,
    visibility   VARCHAR(16) DEFAULT 'private',
    config       JSONB NOT NULL DEFAULT '{}',
    model_config JSONB NOT NULL DEFAULT '{}',
    status       VARCHAR(20) DEFAULT 'active',
    created_at   TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE (org_id, user_id, name)
);
CREATE INDEX ix_agents_org ON agents(org_id);

CREATE TABLE chat_sessions (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id        UUID NOT NULL,
    user_id       UUID NOT NULL,
    agent_id      UUID NOT NULL REFERENCES agents(id),
    title         VARCHAR(500),
    summary       TEXT,
    message_count INTEGER DEFAULT 0,
    source        VARCHAR(16) DEFAULT 'user',
    created_at    TIMESTAMPTZ DEFAULT NOW(),
    updated_at    TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX ix_sessions_org_user ON chat_sessions(org_id, user_id);

CREATE TABLE usage_records (
    id           BIGSERIAL PRIMARY KEY,
    org_id       UUID NOT NULL,
    user_id      UUID NOT NULL,
    metric       VARCHAR(64) NOT NULL,
    metric_value BIGINT NOT NULL,
    model        VARCHAR(64),
    recorded_at  TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX ix_usage_org_time ON usage_records(org_id, recorded_at);

CREATE TABLE audit_logs (
    id       BIGSERIAL PRIMARY KEY,
    org_id   UUID NOT NULL,
    actor    UUID,
    action   VARCHAR(64),
    resource VARCHAR(128),
    detail   JSONB,
    ts       TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX ix_audit_org_time ON audit_logs(org_id, ts);
