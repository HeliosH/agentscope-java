CREATE TABLE worker_heartbeats (
    deployment_scope VARCHAR(128) NOT NULL,
    worker_id UUID NOT NULL,
    capability_hash VARCHAR(64) NOT NULL,
    available_slots INTEGER NOT NULL CHECK (available_slots >= 0),
    heartbeat_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (deployment_scope, worker_id),
    CHECK (char_length(capability_hash) = 64)
);
CREATE INDEX ix_worker_consumer_presence ON worker_heartbeats(deployment_scope, capability_hash, expires_at);
-- Operational liveness never grants business access. Tenant connections cannot enumerate workers.
REVOKE ALL ON worker_heartbeats FROM app;
CREATE INDEX ix_task_recovery_waiting ON task_nodes(updated_at) WHERE status = 'READY' AND recovery_count > 0;
CREATE INDEX ix_model_reasoning_recovery ON model_invocations(org_id, user_id, run_id, task_id)
    WHERE purpose = 'REASONING' AND status = 'FAILED';
