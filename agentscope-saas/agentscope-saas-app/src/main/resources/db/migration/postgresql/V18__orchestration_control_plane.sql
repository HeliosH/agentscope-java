-- Copyright 2024-2026 the original author or authors.
--
-- Licensed under the Apache License, Version 2.0 (the "License");
-- you may not use this file except in compliance with the License.
-- You may obtain a copy of the License at
--
-- http://www.apache.org/licenses/LICENSE-2.0
--
-- Durable enterprise task orchestration. PostgreSQL is the source of truth for Run state,
-- task dependencies, execution attempts, events, approvals, artifacts, and sandbox leases.

CREATE TABLE assistant_runs (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id              UUID NOT NULL,
    user_id             UUID NOT NULL,
    agent_id            UUID NOT NULL REFERENCES agents(id),
    session_id          UUID NOT NULL REFERENCES chat_sessions(id),
    trigger_message_id  UUID REFERENCES chat_messages(id),
    idempotency_key     VARCHAR(255),
    mode                VARCHAR(32) NOT NULL,
    status              VARCHAR(32) NOT NULL,
    cancel_requested    BOOLEAN NOT NULL DEFAULT FALSE,
    next_event_seq      BIGINT NOT NULL DEFAULT 0,
    failure_code        VARCHAR(128),
    failure_message     VARCHAR(2000),
    started_at          TIMESTAMPTZ,
    completed_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version             BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX ix_assistant_runs_org_user_created
    ON assistant_runs(org_id, user_id, created_at DESC);
CREATE INDEX ix_assistant_runs_session_created
    ON assistant_runs(session_id, created_at DESC);
CREATE INDEX ix_assistant_runs_status_created
    ON assistant_runs(status, created_at);
CREATE UNIQUE INDEX ux_assistant_runs_idempotency
    ON assistant_runs(org_id, user_id, agent_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE TABLE task_nodes (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id                  UUID NOT NULL,
    run_id                  UUID NOT NULL REFERENCES assistant_runs(id) ON DELETE CASCADE,
    parent_id               UUID REFERENCES task_nodes(id) ON DELETE SET NULL,
    owner_agent_run_id      UUID,
    title                   VARCHAR(500) NOT NULL,
    task_type               VARCHAR(64) NOT NULL DEFAULT 'agent',
    status                  VARCHAR(32) NOT NULL,
    priority                INTEGER NOT NULL DEFAULT 0,
    input_json              JSONB NOT NULL DEFAULT '{}'::jsonb,
    expected_output_json    JSONB NOT NULL DEFAULT '{}'::jsonb,
    acceptance_json         JSONB NOT NULL DEFAULT '[]'::jsonb,
    workspace_mode          VARCHAR(64) NOT NULL DEFAULT 'NONE',
    max_attempts            INTEGER NOT NULL DEFAULT 1,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at            TIMESTAMPTZ,
    version                 BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX ix_task_nodes_run_status_priority
    ON task_nodes(run_id, status, priority DESC, created_at);
CREATE INDEX ix_task_nodes_org_status
    ON task_nodes(org_id, status, created_at);

CREATE TABLE task_edges (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id          UUID NOT NULL,
    run_id          UUID NOT NULL REFERENCES assistant_runs(id) ON DELETE CASCADE,
    from_task_id    UUID NOT NULL REFERENCES task_nodes(id) ON DELETE CASCADE,
    to_task_id      UUID NOT NULL REFERENCES task_nodes(id) ON DELETE CASCADE,
    edge_type       VARCHAR(32) NOT NULL DEFAULT 'blocks',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_task_edges_no_self_edge CHECK (from_task_id <> to_task_id),
    CONSTRAINT ux_task_edges_unique UNIQUE (run_id, from_task_id, to_task_id, edge_type)
);
CREATE INDEX ix_task_edges_to ON task_edges(to_task_id);

CREATE TABLE agent_runs (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id              UUID NOT NULL,
    run_id              UUID NOT NULL REFERENCES assistant_runs(id) ON DELETE CASCADE,
    task_id             UUID NOT NULL REFERENCES task_nodes(id) ON DELETE CASCADE,
    parent_agent_run_id UUID REFERENCES agent_runs(id) ON DELETE SET NULL,
    agent_type          VARCHAR(128) NOT NULL,
    model_name          VARCHAR(255),
    status              VARCHAR(32) NOT NULL,
    depth               INTEGER NOT NULL DEFAULT 0,
    context_policy      VARCHAR(64) NOT NULL DEFAULT 'FRESH',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at        TIMESTAMPTZ,
    version             BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX ix_agent_runs_run_task ON agent_runs(run_id, task_id);

CREATE TABLE run_attempts (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id              UUID NOT NULL,
    run_id              UUID NOT NULL REFERENCES assistant_runs(id) ON DELETE CASCADE,
    task_id             UUID NOT NULL REFERENCES task_nodes(id) ON DELETE CASCADE,
    agent_run_id        UUID REFERENCES agent_runs(id) ON DELETE SET NULL,
    attempt_no          INTEGER NOT NULL,
    status              VARCHAR(32) NOT NULL,
    lease_owner         VARCHAR(255),
    lease_expires_at    TIMESTAMPTZ,
    heartbeat_at        TIMESTAMPTZ,
    idempotency_key     VARCHAR(255),
    error_code          VARCHAR(128),
    error_message       VARCHAR(2000),
    started_at          TIMESTAMPTZ,
    completed_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version             BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ux_run_attempt_number UNIQUE (task_id, attempt_no)
);
CREATE INDEX ix_run_attempts_ready_lease
    ON run_attempts(status, lease_expires_at, created_at);

CREATE TABLE run_events (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id          UUID NOT NULL,
    user_id         UUID NOT NULL,
    run_id          UUID NOT NULL REFERENCES assistant_runs(id) ON DELETE CASCADE,
    task_id         UUID REFERENCES task_nodes(id) ON DELETE SET NULL,
    agent_run_id    UUID REFERENCES agent_runs(id) ON DELETE SET NULL,
    attempt_id      UUID REFERENCES run_attempts(id) ON DELETE SET NULL,
    seq             BIGINT NOT NULL,
    event_type      VARCHAR(64) NOT NULL,
    payload_json    JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ux_run_events_seq UNIQUE (run_id, seq)
);
CREATE INDEX ix_run_events_org_run_seq ON run_events(org_id, run_id, seq);

CREATE TABLE run_artifacts (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id          UUID NOT NULL,
    run_id          UUID NOT NULL REFERENCES assistant_runs(id) ON DELETE CASCADE,
    task_id         UUID REFERENCES task_nodes(id) ON DELETE SET NULL,
    file_id         UUID REFERENCES files(id) ON DELETE SET NULL,
    file_version_id UUID REFERENCES file_versions(id) ON DELETE SET NULL,
    artifact_type   VARCHAR(64) NOT NULL,
    evidence_json   JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX ix_run_artifacts_run_task ON run_artifacts(run_id, task_id, created_at);

CREATE TABLE run_approvals (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id          UUID NOT NULL,
    run_id          UUID NOT NULL REFERENCES assistant_runs(id) ON DELETE CASCADE,
    task_id         UUID REFERENCES task_nodes(id) ON DELETE SET NULL,
    approval_type   VARCHAR(64) NOT NULL,
    status          VARCHAR(32) NOT NULL,
    request_json    JSONB NOT NULL DEFAULT '{}'::jsonb,
    decision_json   JSONB,
    requested_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    decided_at      TIMESTAMPTZ,
    decided_by      UUID
);
CREATE INDEX ix_run_approvals_run_status ON run_approvals(run_id, status, requested_at);

CREATE TABLE sandbox_leases (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id                  UUID NOT NULL,
    user_id                 UUID NOT NULL,
    run_id                  UUID NOT NULL REFERENCES assistant_runs(id) ON DELETE CASCADE,
    task_id                 UUID REFERENCES task_nodes(id) ON DELETE SET NULL,
    attempt_id              UUID REFERENCES run_attempts(id) ON DELETE SET NULL,
    provider_id             VARCHAR(64) NOT NULL,
    provider_sandbox_id     VARCHAR(255),
    provider_state_json     JSONB,
    image_or_template       VARCHAR(512),
    capabilities_json       JSONB NOT NULL DEFAULT '{}'::jsonb,
    workspace_snapshot_uri  VARCHAR(2048),
    workspace_version       VARCHAR(128),
    status                  VARCHAR(32) NOT NULL,
    lease_owner             VARCHAR(255),
    lease_expires_at        TIMESTAMPTZ,
    last_heartbeat_at       TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    released_at             TIMESTAMPTZ,
    release_error           VARCHAR(2000)
);
CREATE INDEX ix_sandbox_leases_run_status ON sandbox_leases(run_id, status);
CREATE INDEX ix_sandbox_leases_provider_status ON sandbox_leases(provider_id, status, lease_expires_at);

CREATE TABLE orchestration_outbox (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id          UUID NOT NULL,
    aggregate_id    UUID NOT NULL,
    aggregate_type  VARCHAR(64) NOT NULL,
    event_type      VARCHAR(64) NOT NULL,
    payload_json    JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    published_at    TIMESTAMPTZ,
    attempts        INTEGER NOT NULL DEFAULT 0,
    last_error      VARCHAR(2000)
);
CREATE INDEX ix_orchestration_outbox_pending
    ON orchestration_outbox(published_at, created_at);

-- The new tables are tenant-scoped and must be protected by the same RLS rule as existing data.
ALTER TABLE assistant_runs ENABLE ROW LEVEL SECURITY;
ALTER TABLE assistant_runs FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON assistant_runs
    USING (org_id = NULLIF(current_setting('app.current_org', true), '')::uuid);

ALTER TABLE task_nodes ENABLE ROW LEVEL SECURITY;
ALTER TABLE task_nodes FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON task_nodes
    USING (org_id = NULLIF(current_setting('app.current_org', true), '')::uuid);

ALTER TABLE task_edges ENABLE ROW LEVEL SECURITY;
ALTER TABLE task_edges FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON task_edges
    USING (org_id = NULLIF(current_setting('app.current_org', true), '')::uuid);

ALTER TABLE agent_runs ENABLE ROW LEVEL SECURITY;
ALTER TABLE agent_runs FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON agent_runs
    USING (org_id = NULLIF(current_setting('app.current_org', true), '')::uuid);

ALTER TABLE run_attempts ENABLE ROW LEVEL SECURITY;
ALTER TABLE run_attempts FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON run_attempts
    USING (org_id = NULLIF(current_setting('app.current_org', true), '')::uuid);

ALTER TABLE run_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE run_events FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON run_events
    USING (org_id = NULLIF(current_setting('app.current_org', true), '')::uuid);

ALTER TABLE run_artifacts ENABLE ROW LEVEL SECURITY;
ALTER TABLE run_artifacts FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON run_artifacts
    USING (org_id = NULLIF(current_setting('app.current_org', true), '')::uuid);

ALTER TABLE run_approvals ENABLE ROW LEVEL SECURITY;
ALTER TABLE run_approvals FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON run_approvals
    USING (org_id = NULLIF(current_setting('app.current_org', true), '')::uuid);

ALTER TABLE sandbox_leases ENABLE ROW LEVEL SECURITY;
ALTER TABLE sandbox_leases FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON sandbox_leases
    USING (org_id = NULLIF(current_setting('app.current_org', true), '')::uuid);

ALTER TABLE orchestration_outbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE orchestration_outbox FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON orchestration_outbox
    USING (org_id = NULLIF(current_setting('app.current_org', true), '')::uuid);
