CREATE TABLE context_checkpoints (
    id                      UUID DEFAULT RANDOM_UUID() PRIMARY KEY,
    org_id                  UUID NOT NULL,
    run_id                  UUID NOT NULL REFERENCES assistant_runs(id) ON DELETE CASCADE,
    task_id                 UUID REFERENCES task_nodes(id) ON DELETE SET NULL,
    agent_run_id            UUID NOT NULL REFERENCES agent_runs(id) ON DELETE CASCADE,
    attempt_id              UUID REFERENCES run_attempts(id) ON DELETE SET NULL,
    history_revision        BIGINT NOT NULL,
    step_id                 VARCHAR(128) NOT NULL,
    history_hash            VARCHAR(64) NOT NULL,
    summary                 CHARACTER LARGE OBJECT NOT NULL DEFAULT '',
    retained_tail_json      JSON NOT NULL DEFAULT '[]',
    retained_facts_version  VARCHAR(128),
    pending_operations_json JSON NOT NULL DEFAULT '[]',
    workspace_version       VARCHAR(255),
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ux_context_checkpoints_revision
        UNIQUE (org_id, run_id, agent_run_id, history_revision),
    CONSTRAINT ck_context_checkpoints_revision CHECK (history_revision > 0)
);

CREATE INDEX ix_context_checkpoints_run ON context_checkpoints(run_id);
CREATE INDEX ix_context_checkpoints_task ON context_checkpoints(task_id);
CREATE INDEX ix_context_checkpoints_attempt ON context_checkpoints(attempt_id);
CREATE INDEX ix_context_checkpoints_latest
    ON context_checkpoints(org_id, run_id, agent_run_id, history_revision DESC);
