CREATE UNIQUE INDEX ux_tool_operations_org_run_id ON tool_operations(org_id,run_id,id);
CREATE INDEX ix_tool_operations_org_status_id ON tool_operations(org_id,status,id);
CREATE TABLE tool_operation_resolutions (
    id UUID PRIMARY KEY,
    org_id UUID NOT NULL,
    run_id UUID NOT NULL REFERENCES assistant_runs(id) ON DELETE CASCADE,
    operation_id UUID NOT NULL,
    sequence_no BIGINT NOT NULL CHECK (sequence_no > 0),
    operation_version BIGINT NOT NULL CHECK (operation_version >= 0),
    actor_id UUID NOT NULL,
    command_key VARCHAR(128) NOT NULL,
    command_hash VARCHAR(64) NOT NULL,
    decision VARCHAR(32) NOT NULL CHECK (decision IN ('CONFIRMED_SUCCESS','CONFIRMED_NOT_PERFORMED','UNRESOLVED')),
    reason VARCHAR(2000) NOT NULL,
    evidence_ref VARCHAR(2048) NOT NULL,
    result_json JSONB,
    source_fact_json JSONB NOT NULL,
    replay_approved BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY(org_id,run_id,operation_id) REFERENCES tool_operations(org_id,run_id,id) ON DELETE CASCADE,
    UNIQUE(operation_id,sequence_no),
    UNIQUE(org_id,actor_id,command_key),
    CHECK ((decision = 'CONFIRMED_SUCCESS' AND result_json IS NOT NULL AND NOT replay_approved)
        OR (decision <> 'CONFIRMED_SUCCESS' AND result_json IS NULL)),
    CHECK (NOT replay_approved OR decision = 'CONFIRMED_NOT_PERFORMED')
);
CREATE INDEX ix_tool_resolutions_latest ON tool_operation_resolutions(org_id,run_id,operation_id,sequence_no DESC);
CREATE TABLE tool_operation_replay_permits (
    id UUID PRIMARY KEY,
    org_id UUID NOT NULL,
    run_id UUID NOT NULL REFERENCES assistant_runs(id) ON DELETE CASCADE,
    operation_id UUID NOT NULL,
    resolution_id UUID NOT NULL UNIQUE REFERENCES tool_operation_resolutions(id) ON DELETE CASCADE,
    operation_version BIGINT NOT NULL CHECK (operation_version >= 0),
    actor_id UUID NOT NULL,
    tool_name VARCHAR(255) NOT NULL,
    tool_call_id VARCHAR(255) NOT NULL,
    input_hash VARCHAR(64) NOT NULL,
    valid_until TIMESTAMPTZ NOT NULL,
    invocation_id UUID,
    consumed_at TIMESTAMPTZ,
    FOREIGN KEY(org_id,run_id,operation_id) REFERENCES tool_operations(org_id,run_id,id) ON DELETE CASCADE,
    CHECK ((invocation_id IS NULL AND consumed_at IS NULL) OR (invocation_id IS NOT NULL AND consumed_at IS NOT NULL))
);
CREATE INDEX ix_tool_replay_permits_operation ON tool_operation_replay_permits(org_id,run_id,operation_id);
ALTER TABLE tool_operation_resolutions ENABLE ROW LEVEL SECURITY;
ALTER TABLE tool_operation_resolutions FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON tool_operation_resolutions
    USING (org_id = (SELECT NULLIF(current_setting('app.current_org',true),'')::uuid))
    WITH CHECK (org_id = (SELECT NULLIF(current_setting('app.current_org',true),'')::uuid));
ALTER TABLE tool_operation_replay_permits ENABLE ROW LEVEL SECURITY;
ALTER TABLE tool_operation_replay_permits FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON tool_operation_replay_permits
    USING (org_id = (SELECT NULLIF(current_setting('app.current_org',true),'')::uuid))
    WITH CHECK (org_id = (SELECT NULLIF(current_setting('app.current_org',true),'')::uuid));
REVOKE ALL ON tool_operation_resolutions,tool_operation_replay_permits FROM app;
GRANT SELECT,INSERT ON tool_operation_resolutions,tool_operation_replay_permits TO app;
