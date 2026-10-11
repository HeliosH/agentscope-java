ALTER TABLE runtime_message_bodies ADD CONSTRAINT uq_runtime_body_fallback_owner
    UNIQUE (id, org_id, user_id, agent_id, session_id);
CREATE TABLE runtime_body_fallbacks (
    body_id UUID PRIMARY KEY,
    org_id UUID NOT NULL,
    user_id UUID NOT NULL,
    agent_id UUID NOT NULL,
    session_id UUID NOT NULL,
    payload TEXT NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes > 0 AND size_bytes <= 1048576),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    claim_token UUID,
    lease_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (octet_length(payload) = size_bytes),
    CHECK ((claim_token IS NULL) = (lease_until IS NULL)),
    FOREIGN KEY (body_id, org_id, user_id, agent_id, session_id)
        REFERENCES runtime_message_bodies(id, org_id, user_id, agent_id, session_id)
);
CREATE INDEX ix_runtime_fallback_due ON runtime_body_fallbacks(next_attempt_at, body_id);
CREATE INDEX ix_runtime_fallback_quota ON runtime_body_fallbacks(org_id, user_id);
ALTER TABLE runtime_body_fallbacks ENABLE ROW LEVEL SECURITY;
ALTER TABLE runtime_body_fallbacks FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON runtime_body_fallbacks
    USING (org_id = NULLIF(current_setting('app.current_org', true), '')::uuid)
    WITH CHECK (org_id = NULLIF(current_setting('app.current_org', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON runtime_body_fallbacks TO app;
