ALTER TABLE assistant_runs ADD CONSTRAINT ux_run_policy_owner UNIQUE (id, org_id);
CREATE TABLE run_execution_policies (
    run_id UUID PRIMARY KEY,
    org_id UUID NOT NULL,
    version INTEGER NOT NULL CHECK (version = 1),
    deployment_scope VARCHAR(128) NOT NULL,
    capability_hash VARCHAR(64) NOT NULL,
    max_files INTEGER NOT NULL CHECK (max_files > 0 AND max_files < 2147483647),
    max_file_bytes BIGINT NOT NULL CHECK (max_file_bytes > 0),
    max_workspace_bytes BIGINT NOT NULL CHECK (max_workspace_bytes >= max_file_bytes),
    recovery_enabled BOOLEAN NOT NULL,
    recovery_mode VARCHAR(16) NOT NULL CHECK (recovery_mode IN ('REQUEST_ONLY', 'DURABLE')),
    recovery_max_attempts INTEGER NOT NULL CHECK (recovery_max_attempts > 0),
    recovery_initial_delay_millis BIGINT NOT NULL CHECK (recovery_initial_delay_millis >= 0),
    recovery_max_delay_millis BIGINT NOT NULL CHECK (recovery_max_delay_millis >= recovery_initial_delay_millis),
    accepted_at TIMESTAMP WITH TIME ZONE NOT NULL,
    FOREIGN KEY (run_id, org_id) REFERENCES assistant_runs(id, org_id) ON DELETE CASCADE
);
CREATE INDEX ix_run_execution_policy_owner ON run_execution_policies(org_id, run_id);
CREATE INDEX ix_run_execution_policy_consumer ON run_execution_policies(deployment_scope, capability_hash, run_id);
ALTER TABLE worker_heartbeats ADD COLUMN max_archive_bytes BIGINT NOT NULL DEFAULT 0 CHECK (max_archive_bytes >= 0);
ALTER TABLE worker_heartbeats ADD COLUMN max_file_bytes BIGINT NOT NULL DEFAULT 0 CHECK (max_file_bytes >= 0);
