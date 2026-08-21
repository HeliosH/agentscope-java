-- Immutable evidence of the model/tool capability surface used by an Agent Run.
ALTER TABLE agent_runs
    ADD COLUMN runtime_capability_snapshot_json JSONB,
    ADD COLUMN runtime_capability_snapshot_hash VARCHAR(64),
    ADD COLUMN runtime_capability_captured_at TIMESTAMPTZ;

CREATE INDEX ix_agent_runs_runtime_capability_hash
    ON agent_runs(runtime_capability_snapshot_hash)
    WHERE runtime_capability_snapshot_hash IS NOT NULL;
