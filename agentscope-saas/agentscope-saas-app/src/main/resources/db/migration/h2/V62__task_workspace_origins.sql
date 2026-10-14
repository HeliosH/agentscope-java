ALTER TABLE sandbox_leases ADD CONSTRAINT uq_sandbox_lease_run UNIQUE(id,org_id,run_id);
CREATE INDEX ix_sandbox_parent_checkpoint ON sandbox_leases(org_id,run_id,task_id,created_at DESC);
CREATE TABLE task_workspace_origins (
    task_id UUID PRIMARY KEY REFERENCES task_nodes(id) ON DELETE CASCADE,
    org_id UUID NOT NULL,
    run_id UUID NOT NULL REFERENCES assistant_runs(id) ON DELETE CASCADE,
    user_id UUID NOT NULL,
    agent_id UUID NOT NULL,
    session_id UUID NOT NULL,
    session_generation BIGINT NOT NULL CHECK(session_generation >= 0),
    source_lease_id UUID NOT NULL,
    parent_task_id UUID NOT NULL,
    parent_attempt_id UUID NOT NULL,
    checkpoint_uri VARCHAR(2048) NOT NULL,
    workspace_version VARCHAR(128) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY(source_lease_id,org_id,run_id) REFERENCES sandbox_leases(id,org_id,run_id),
    CHECK(task_id <> parent_task_id)
);
CREATE INDEX ix_task_workspace_origins_org ON task_workspace_origins(org_id,run_id);
CREATE INDEX ix_task_workspace_origins_source ON task_workspace_origins(source_lease_id,org_id,run_id);
