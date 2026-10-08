CREATE TABLE model_invocations (
    id UUID PRIMARY KEY,
    org_id UUID NOT NULL REFERENCES orgs(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    run_id UUID REFERENCES assistant_runs(id) ON DELETE CASCADE,
    task_id UUID REFERENCES task_nodes(id) ON DELETE SET NULL,
    agent_run_id UUID REFERENCES agent_runs(id) ON DELETE SET NULL,
    attempt_id UUID REFERENCES run_attempts(id) ON DELETE SET NULL,
    lease_owner VARCHAR(255), purpose VARCHAR(32) NOT NULL,
    model_id VARCHAR(128) NOT NULL, route_version VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'STARTED',
    reserved_tokens BIGINT NOT NULL CHECK (reserved_tokens >= 0),
    reserved_cost_micros BIGINT NOT NULL CHECK (reserved_cost_micros >= 0),
    estimated_input_tokens BIGINT NOT NULL CHECK (estimated_input_tokens >= 0),
    input_tokens BIGINT NOT NULL DEFAULT 0 CHECK (input_tokens >= 0),
    output_tokens BIGINT NOT NULL DEFAULT 0 CHECK (output_tokens >= 0),
    total_tokens BIGINT NOT NULL DEFAULT 0 CHECK (total_tokens >= 0),
    cost_micros BIGINT NOT NULL DEFAULT 0 CHECK (cost_micros >= 0),
    usage_source VARCHAR(32), reason_code VARCHAR(64),
    deadline_at TIMESTAMP WITH TIME ZONE NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMP WITH TIME ZONE,
    CHECK (purpose IN ('REASONING','COMPACTION','MEMORY_EXTRACT','MEMORY_CONSOLIDATE','VERIFY')),
    CHECK (status IN ('STARTED','SUCCEEDED','FAILED','CANCELLED'))
);
CREATE INDEX ix_model_invocations_org_day ON model_invocations(org_id, started_at);
CREATE INDEX ix_model_invocations_user_day ON model_invocations(org_id, user_id, started_at);
CREATE INDEX ix_model_invocations_user_fk ON model_invocations(user_id);
CREATE INDEX ix_model_invocations_run ON model_invocations(run_id);
CREATE INDEX ix_model_invocations_task ON model_invocations(task_id);
CREATE INDEX ix_model_invocations_agent_run ON model_invocations(agent_run_id);
CREATE INDEX ix_model_invocations_attempt ON model_invocations(attempt_id);
CREATE INDEX ix_model_invocations_pending ON model_invocations(org_id, run_id, status, deadline_at);
CREATE INDEX ix_usage_org_metric_time ON usage_records(org_id, metric, recorded_at);
CREATE INDEX ix_usage_user_metric_time ON usage_records(org_id, user_id, metric, recorded_at);
CREATE TABLE model_invocation_policies (
    org_id UUID NOT NULL REFERENCES orgs(id) ON DELETE CASCADE,
    purpose VARCHAR(32) NOT NULL, model_id VARCHAR(128),
    max_input_tokens INTEGER NOT NULL CHECK (max_input_tokens > 0),
    max_output_tokens INTEGER NOT NULL CHECK (max_output_tokens > 0),
    timeout_seconds INTEGER NOT NULL CHECK (timeout_seconds > 0),
    version VARCHAR(64) NOT NULL,
    PRIMARY KEY (org_id, purpose),
    CHECK (purpose IN ('COMPACTION','MEMORY_EXTRACT','MEMORY_CONSOLIDATE','VERIFY'))
);
