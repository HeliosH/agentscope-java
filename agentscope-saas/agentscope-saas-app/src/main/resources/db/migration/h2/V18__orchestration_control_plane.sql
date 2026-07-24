-- H2 counterpart of V18. PostgreSQL RLS is intentionally absent in H2; service-layer tenant
-- filtering remains mandatory in local and test profiles.

CREATE TABLE assistant_runs (
    id UUID DEFAULT RANDOM_UUID() PRIMARY KEY,
    org_id UUID NOT NULL,
    user_id UUID NOT NULL,
    agent_id UUID NOT NULL REFERENCES agents(id),
    session_id UUID NOT NULL REFERENCES chat_sessions(id),
    trigger_message_id UUID REFERENCES chat_messages(id),
    idempotency_key VARCHAR(255),
    mode VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
    next_event_seq BIGINT NOT NULL DEFAULT 0,
    failure_code VARCHAR(128),
    failure_message VARCHAR(2000),
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX ix_assistant_runs_org_user_created ON assistant_runs(org_id, user_id, created_at DESC);
CREATE INDEX ix_assistant_runs_session_created ON assistant_runs(session_id, created_at DESC);
CREATE INDEX ix_assistant_runs_status_created ON assistant_runs(status, created_at);
CREATE UNIQUE INDEX ux_assistant_runs_idempotency
    ON assistant_runs(org_id, user_id, agent_id, idempotency_key);

CREATE TABLE task_nodes (
    id UUID DEFAULT RANDOM_UUID() PRIMARY KEY,
    org_id UUID NOT NULL,
    run_id UUID NOT NULL REFERENCES assistant_runs(id) ON DELETE CASCADE,
    parent_id UUID REFERENCES task_nodes(id) ON DELETE SET NULL,
    owner_agent_run_id UUID,
    title VARCHAR(500) NOT NULL,
    task_type VARCHAR(64) NOT NULL DEFAULT 'agent',
    status VARCHAR(32) NOT NULL,
    priority INTEGER NOT NULL DEFAULT 0,
    input_json JSON NOT NULL DEFAULT '{}',
    expected_output_json JSON NOT NULL DEFAULT '{}',
    acceptance_json JSON NOT NULL DEFAULT '[]',
    workspace_mode VARCHAR(64) NOT NULL DEFAULT 'NONE',
    max_attempts INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP WITH TIME ZONE,
    version BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX ix_task_nodes_run_status_priority ON task_nodes(run_id, status, priority DESC, created_at);
CREATE INDEX ix_task_nodes_org_status ON task_nodes(org_id, status, created_at);

CREATE TABLE task_edges (
    id UUID DEFAULT RANDOM_UUID() PRIMARY KEY,
    org_id UUID NOT NULL,
    run_id UUID NOT NULL REFERENCES assistant_runs(id) ON DELETE CASCADE,
    from_task_id UUID NOT NULL REFERENCES task_nodes(id) ON DELETE CASCADE,
    to_task_id UUID NOT NULL REFERENCES task_nodes(id) ON DELETE CASCADE,
    edge_type VARCHAR(32) NOT NULL DEFAULT 'blocks',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_task_edges_no_self_edge CHECK (from_task_id <> to_task_id),
    CONSTRAINT ux_task_edges_unique UNIQUE (run_id, from_task_id, to_task_id, edge_type)
);
CREATE INDEX ix_task_edges_to ON task_edges(to_task_id);

CREATE TABLE agent_runs (
    id UUID DEFAULT RANDOM_UUID() PRIMARY KEY,
    org_id UUID NOT NULL,
    run_id UUID NOT NULL REFERENCES assistant_runs(id) ON DELETE CASCADE,
    task_id UUID NOT NULL REFERENCES task_nodes(id) ON DELETE CASCADE,
    parent_agent_run_id UUID REFERENCES agent_runs(id) ON DELETE SET NULL,
    agent_type VARCHAR(128) NOT NULL,
    model_name VARCHAR(255),
    status VARCHAR(32) NOT NULL,
    depth INTEGER NOT NULL DEFAULT 0,
    context_policy VARCHAR(64) NOT NULL DEFAULT 'FRESH',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP WITH TIME ZONE,
    version BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX ix_agent_runs_run_task ON agent_runs(run_id, task_id);

CREATE TABLE run_attempts (
    id UUID DEFAULT RANDOM_UUID() PRIMARY KEY,
    org_id UUID NOT NULL,
    run_id UUID NOT NULL REFERENCES assistant_runs(id) ON DELETE CASCADE,
    task_id UUID NOT NULL REFERENCES task_nodes(id) ON DELETE CASCADE,
    agent_run_id UUID REFERENCES agent_runs(id) ON DELETE SET NULL,
    attempt_no INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL,
    lease_owner VARCHAR(255),
    lease_expires_at TIMESTAMP WITH TIME ZONE,
    heartbeat_at TIMESTAMP WITH TIME ZONE,
    idempotency_key VARCHAR(255),
    error_code VARCHAR(128),
    error_message VARCHAR(2000),
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ux_run_attempt_number UNIQUE (task_id, attempt_no)
);
CREATE INDEX ix_run_attempts_ready_lease ON run_attempts(status, lease_expires_at, created_at);

CREATE TABLE run_events (
    id UUID DEFAULT RANDOM_UUID() PRIMARY KEY,
    org_id UUID NOT NULL,
    user_id UUID NOT NULL,
    run_id UUID NOT NULL REFERENCES assistant_runs(id) ON DELETE CASCADE,
    task_id UUID REFERENCES task_nodes(id) ON DELETE SET NULL,
    agent_run_id UUID REFERENCES agent_runs(id) ON DELETE SET NULL,
    attempt_id UUID REFERENCES run_attempts(id) ON DELETE SET NULL,
    seq BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload_json JSON NOT NULL DEFAULT '{}',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ux_run_events_seq UNIQUE (run_id, seq)
);
CREATE INDEX ix_run_events_org_run_seq ON run_events(org_id, run_id, seq);

CREATE TABLE run_artifacts (
    id UUID DEFAULT RANDOM_UUID() PRIMARY KEY,
    org_id UUID NOT NULL,
    run_id UUID NOT NULL REFERENCES assistant_runs(id) ON DELETE CASCADE,
    task_id UUID REFERENCES task_nodes(id) ON DELETE SET NULL,
    file_id UUID REFERENCES files(id) ON DELETE SET NULL,
    file_version_id UUID REFERENCES file_versions(id) ON DELETE SET NULL,
    artifact_type VARCHAR(64) NOT NULL,
    evidence_json JSON NOT NULL DEFAULT '{}',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX ix_run_artifacts_run_task ON run_artifacts(run_id, task_id, created_at);

CREATE TABLE run_approvals (
    id UUID DEFAULT RANDOM_UUID() PRIMARY KEY,
    org_id UUID NOT NULL,
    run_id UUID NOT NULL REFERENCES assistant_runs(id) ON DELETE CASCADE,
    task_id UUID REFERENCES task_nodes(id) ON DELETE SET NULL,
    approval_type VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    request_json JSON NOT NULL DEFAULT '{}',
    decision_json JSON,
    requested_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    decided_at TIMESTAMP WITH TIME ZONE,
    decided_by UUID
);
CREATE INDEX ix_run_approvals_run_status ON run_approvals(run_id, status, requested_at);

CREATE TABLE sandbox_leases (
    id UUID DEFAULT RANDOM_UUID() PRIMARY KEY,
    org_id UUID NOT NULL,
    user_id UUID NOT NULL,
    run_id UUID NOT NULL REFERENCES assistant_runs(id) ON DELETE CASCADE,
    task_id UUID REFERENCES task_nodes(id) ON DELETE SET NULL,
    attempt_id UUID REFERENCES run_attempts(id) ON DELETE SET NULL,
    provider_id VARCHAR(64) NOT NULL,
    provider_sandbox_id VARCHAR(255),
    provider_state_json JSON,
    image_or_template VARCHAR(512),
    capabilities_json JSON NOT NULL DEFAULT '{}',
    workspace_snapshot_uri VARCHAR(2048),
    workspace_version VARCHAR(128),
    status VARCHAR(32) NOT NULL,
    lease_owner VARCHAR(255),
    lease_expires_at TIMESTAMP WITH TIME ZONE,
    last_heartbeat_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    released_at TIMESTAMP WITH TIME ZONE,
    release_error VARCHAR(2000)
);
CREATE INDEX ix_sandbox_leases_run_status ON sandbox_leases(run_id, status);
CREATE INDEX ix_sandbox_leases_provider_status ON sandbox_leases(provider_id, status, lease_expires_at);

CREATE TABLE orchestration_outbox (
    id UUID DEFAULT RANDOM_UUID() PRIMARY KEY,
    org_id UUID NOT NULL,
    aggregate_id UUID NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload_json JSON NOT NULL DEFAULT '{}',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMP WITH TIME ZONE,
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error VARCHAR(2000)
);
CREATE INDEX ix_orchestration_outbox_pending ON orchestration_outbox(published_at, created_at);
