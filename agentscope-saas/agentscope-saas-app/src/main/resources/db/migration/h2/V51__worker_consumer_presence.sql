CREATE TABLE worker_heartbeats (
    deployment_scope VARCHAR(128) NOT NULL,
    worker_id UUID NOT NULL,
    capability_hash VARCHAR(64) NOT NULL,
    available_slots INTEGER NOT NULL CHECK (available_slots >= 0),
    heartbeat_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (deployment_scope, worker_id),
    CHECK (char_length(capability_hash) = 64)
);
CREATE INDEX ix_worker_consumer_presence ON worker_heartbeats(deployment_scope, capability_hash, expires_at);
CREATE INDEX ix_task_recovery_waiting ON task_nodes(status, recovery_count, updated_at);
CREATE INDEX ix_model_reasoning_recovery ON model_invocations(org_id, user_id, run_id, task_id, purpose, status);
