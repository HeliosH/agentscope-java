CREATE TABLE context_checkpoint_body_refs (
    checkpoint_id UUID NOT NULL REFERENCES context_checkpoints(id) ON DELETE CASCADE,
    org_id UUID NOT NULL,
    body_id UUID NOT NULL REFERENCES runtime_message_bodies(id),
    PRIMARY KEY (checkpoint_id, body_id)
);
CREATE INDEX ix_checkpoint_body_refs_body ON context_checkpoint_body_refs(body_id);
ALTER TABLE context_checkpoint_body_refs ENABLE ROW LEVEL SECURITY;
ALTER TABLE context_checkpoint_body_refs FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON context_checkpoint_body_refs
    USING (org_id = NULLIF(current_setting('app.current_org', true), '')::uuid)
    WITH CHECK (org_id = NULLIF(current_setting('app.current_org', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE, DELETE ON context_checkpoint_body_refs TO app;
