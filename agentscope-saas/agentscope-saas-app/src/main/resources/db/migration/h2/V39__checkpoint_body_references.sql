CREATE TABLE context_checkpoint_body_refs (
    checkpoint_id UUID NOT NULL REFERENCES context_checkpoints(id) ON DELETE CASCADE,
    org_id UUID NOT NULL,
    body_id UUID NOT NULL REFERENCES runtime_message_bodies(id),
    PRIMARY KEY (checkpoint_id, body_id)
);
CREATE INDEX ix_checkpoint_body_refs_body ON context_checkpoint_body_refs(body_id);
