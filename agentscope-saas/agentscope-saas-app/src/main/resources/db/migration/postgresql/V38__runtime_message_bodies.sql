CREATE TABLE runtime_message_bodies (
    id UUID PRIMARY KEY,
    org_id UUID NOT NULL,
    user_id UUID NOT NULL,
    agent_id UUID NOT NULL,
    session_id UUID NOT NULL,
    object_key VARCHAR(1024) NOT NULL UNIQUE,
    backend VARCHAR(32) NOT NULL CHECK (backend IN ('pg', 'minio')),
    sha256 VARCHAR(64) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes > 0 AND size_bytes <= 33554432),
    status VARCHAR(32) NOT NULL CHECK (status IN ('STAGED', 'READY', 'DELETING', 'DELETED')),
    eligible_at TIMESTAMPTZ NOT NULL,
    claim_token UUID,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
-- Ownership hints deliberately survive parent deletion so physical bytes remain collectable.
CREATE INDEX ix_runtime_bodies_gc ON runtime_message_bodies(eligible_at, id);
CREATE INDEX ix_runtime_bodies_owner ON runtime_message_bodies(org_id, user_id, agent_id, session_id);
ALTER TABLE runtime_messages ADD COLUMN body_id UUID REFERENCES runtime_message_bodies(id);
CREATE INDEX ix_runtime_messages_body ON runtime_messages(body_id) WHERE body_id IS NOT NULL;
ALTER TABLE runtime_message_bodies ENABLE ROW LEVEL SECURITY;
ALTER TABLE runtime_message_bodies FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON runtime_message_bodies
    USING (org_id = NULLIF(current_setting('app.current_org', true), '')::uuid)
    WITH CHECK (org_id = NULLIF(current_setting('app.current_org', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON runtime_message_bodies TO app;
