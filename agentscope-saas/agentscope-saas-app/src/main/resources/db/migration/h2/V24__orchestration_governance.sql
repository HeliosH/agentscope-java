-- H2 counterpart of V24. PostgreSQL partial indexes are represented as ordinary indexes.

ALTER TABLE assistant_runs ADD COLUMN token_budget BIGINT;
ALTER TABLE assistant_runs ADD COLUMN consumed_tokens BIGINT NOT NULL DEFAULT 0;
ALTER TABLE assistant_runs ADD COLUMN cost_budget_micros BIGINT;
ALTER TABLE assistant_runs ADD COLUMN consumed_cost_micros BIGINT NOT NULL DEFAULT 0;
ALTER TABLE assistant_runs ADD COLUMN model_call_budget INTEGER;
ALTER TABLE assistant_runs ADD COLUMN consumed_model_calls INTEGER NOT NULL DEFAULT 0;
ALTER TABLE assistant_runs ADD COLUMN deadline_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE task_nodes ADD COLUMN token_budget BIGINT;
ALTER TABLE task_nodes ADD COLUMN consumed_tokens BIGINT NOT NULL DEFAULT 0;
ALTER TABLE task_nodes ADD COLUMN cost_budget_micros BIGINT;
ALTER TABLE task_nodes ADD COLUMN consumed_cost_micros BIGINT NOT NULL DEFAULT 0;
ALTER TABLE task_nodes ADD COLUMN model_call_budget INTEGER;
ALTER TABLE task_nodes ADD COLUMN consumed_model_calls INTEGER NOT NULL DEFAULT 0;
ALTER TABLE task_nodes ADD COLUMN deadline_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE agent_runs ADD COLUMN permission_snapshot_json JSON NOT NULL DEFAULT '{}';
ALTER TABLE agent_runs ADD COLUMN permission_snapshot_hash VARCHAR(64);

CREATE INDEX ix_assistant_runs_deadline ON assistant_runs(status, deadline_at);
CREATE INDEX ix_task_nodes_deadline ON task_nodes(status, deadline_at);
