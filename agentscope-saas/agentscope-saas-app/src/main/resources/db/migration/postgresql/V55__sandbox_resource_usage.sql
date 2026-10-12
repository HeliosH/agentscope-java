-- Resource evidence outlives Run/session deletion so a revoked worker cannot orphan its backend.
CREATE TABLE sandbox_runtime_resources (
    id UUID PRIMARY KEY, org_id UUID NOT NULL, user_id UUID NOT NULL,
    agent_id UUID NOT NULL, run_id UUID NOT NULL, session_id UUID NOT NULL,
    session_generation BIGINT NOT NULL CHECK (session_generation >= 0),
    provider_id VARCHAR(64) NOT NULL, deployment_key VARCHAR(64) NOT NULL, slot_key VARCHAR(1024) NOT NULL,
    external_id VARCHAR(512),
    status VARCHAR(32) NOT NULL CHECK (status IN ('PROVISIONING','ACTIVE','FINALIZING','DRAINING','RETAINED','RELEASING','RELEASE_FAILED','RELEASED')),
    release_token UUID, release_until TIMESTAMPTZ,
    release_attempts INTEGER NOT NULL DEFAULT 0 CHECK (release_attempts >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX ix_sandbox_resource_owner ON sandbox_runtime_resources(org_id,user_id,status);
CREATE INDEX ix_sandbox_resource_slot ON sandbox_runtime_resources(org_id,user_id,provider_id,deployment_key,slot_key);
CREATE INDEX ix_sandbox_resource_backend ON sandbox_runtime_resources(org_id,user_id,provider_id,external_id,status);
CREATE INDEX ix_sandbox_resource_gc ON sandbox_runtime_resources(deployment_key,status,release_until);
CREATE TABLE sandbox_usage_refs (
    id UUID PRIMARY KEY, resource_id UUID NOT NULL REFERENCES sandbox_runtime_resources(id) ON DELETE CASCADE,
    org_id UUID NOT NULL, user_id UUID NOT NULL, task_id UUID NOT NULL, attempt_id UUID NOT NULL,
    lease_owner VARCHAR(255), parent_id UUID,
    status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE','CLOSED','EXPIRED')),
    expires_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX ix_sandbox_usage_resource ON sandbox_usage_refs(resource_id,status,expires_at);
CREATE INDEX ix_sandbox_usage_owner ON sandbox_usage_refs(org_id,user_id,resource_id);
ALTER TABLE sandbox_runtime_resources ENABLE ROW LEVEL SECURITY;
ALTER TABLE sandbox_runtime_resources FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON sandbox_runtime_resources
    USING (org_id = NULLIF(current_setting('app.current_org', true), '')::uuid)
    WITH CHECK (org_id = NULLIF(current_setting('app.current_org', true), '')::uuid);
ALTER TABLE sandbox_usage_refs ENABLE ROW LEVEL SECURITY;
ALTER TABLE sandbox_usage_refs FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON sandbox_usage_refs
    USING (org_id = NULLIF(current_setting('app.current_org', true), '')::uuid)
    WITH CHECK (org_id = NULLIF(current_setting('app.current_org', true), '')::uuid);
GRANT SELECT, INSERT ON sandbox_runtime_resources, sandbox_usage_refs TO app;
GRANT UPDATE(external_id,status,release_token,release_until,release_attempts,updated_at) ON sandbox_runtime_resources TO app;
GRANT UPDATE(status,expires_at,updated_at) ON sandbox_usage_refs TO app;
