ALTER TABLE agent_runs ADD COLUMN runtime_capability_snapshot_json JSON;
ALTER TABLE agent_runs ADD COLUMN runtime_capability_snapshot_hash VARCHAR(64);
ALTER TABLE agent_runs ADD COLUMN runtime_capability_captured_at TIMESTAMP WITH TIME ZONE;

CREATE INDEX ix_agent_runs_runtime_capability_hash
    ON agent_runs(runtime_capability_snapshot_hash);
