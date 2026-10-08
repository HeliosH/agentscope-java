ALTER TABLE memory_events ADD COLUMN sync_claim_token UUID;
ALTER TABLE memory_events ADD COLUMN sync_lease_until TIMESTAMP WITH TIME ZONE;
ALTER TABLE memory_events ADD COLUMN sync_next_attempt_at TIMESTAMP WITH TIME ZONE;

CREATE INDEX ix_memory_projection_due
    ON memory_events (source, event_type, sync_status, sync_next_attempt_at, created_at, id);
CREATE INDEX ix_memory_projection_expired
    ON memory_events (source, event_type, sync_status, sync_lease_until, updated_at, id);
