-- Run/task resource budgets and immutable permission snapshots for durable orchestration.

ALTER TABLE assistant_runs
    ADD COLUMN token_budget BIGINT,
    ADD COLUMN consumed_tokens BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN cost_budget_micros BIGINT,
    ADD COLUMN consumed_cost_micros BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN model_call_budget INTEGER,
    ADD COLUMN consumed_model_calls INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN deadline_at TIMESTAMPTZ;

ALTER TABLE task_nodes
    ADD COLUMN token_budget BIGINT,
    ADD COLUMN consumed_tokens BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN cost_budget_micros BIGINT,
    ADD COLUMN consumed_cost_micros BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN model_call_budget INTEGER,
    ADD COLUMN consumed_model_calls INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN deadline_at TIMESTAMPTZ;

ALTER TABLE agent_runs
    ADD COLUMN permission_snapshot_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN permission_snapshot_hash VARCHAR(64);

CREATE INDEX ix_assistant_runs_deadline
    ON assistant_runs(status, deadline_at)
    WHERE status = 'RUNNING' AND deadline_at IS NOT NULL;

CREATE INDEX ix_task_nodes_deadline
    ON task_nodes(status, deadline_at)
    WHERE status IN ('READY', 'CLAIMED', 'RUNNING') AND deadline_at IS NOT NULL;
