CREATE TABLE tool_operations (
    id                  UUID DEFAULT RANDOM_UUID() PRIMARY KEY,
    org_id              UUID NOT NULL,
    run_id              UUID NOT NULL REFERENCES assistant_runs(id) ON DELETE CASCADE,
    task_id             UUID REFERENCES task_nodes(id) ON DELETE SET NULL,
    agent_run_id        UUID REFERENCES agent_runs(id) ON DELETE SET NULL,
    attempt_id          UUID REFERENCES run_attempts(id) ON DELETE SET NULL,
    operation_key       VARCHAR(512) NOT NULL,
    invocation_id       UUID NOT NULL,
    step_id             VARCHAR(128),
    tool_call_id        VARCHAR(255) NOT NULL,
    tool_name           VARCHAR(255) NOT NULL,
    input_hash          VARCHAR(64) NOT NULL,
    input_json          JSON NOT NULL DEFAULT '{}',
    retry_safety        VARCHAR(32) NOT NULL,
    status              VARCHAR(32) NOT NULL,
    result_json         JSON,
    error_type          VARCHAR(255),
    error_message       VARCHAR(2000),
    prepared_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at          TIMESTAMP WITH TIME ZONE,
    completed_at        TIMESTAMP WITH TIME ZONE,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version             BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ux_tool_operations_business_key UNIQUE (org_id, run_id, operation_key),
    CONSTRAINT ck_tool_operations_retry_safety
        CHECK (retry_safety IN ('NEVER', 'READ_ONLY', 'IDEMPOTENT')),
    CONSTRAINT ck_tool_operations_status
        CHECK (status IN ('CLAIMED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED',
                          'SUSPENDED', 'OUTCOME_UNKNOWN'))
);

CREATE INDEX ix_tool_operations_run ON tool_operations(run_id);
CREATE INDEX ix_tool_operations_task ON tool_operations(task_id);
CREATE INDEX ix_tool_operations_agent_run ON tool_operations(agent_run_id);
CREATE INDEX ix_tool_operations_attempt ON tool_operations(attempt_id);
CREATE INDEX ix_tool_operations_org_status_updated
    ON tool_operations(org_id, status, updated_at);
