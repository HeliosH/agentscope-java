-- Projection state is mutable; the source content remains immutable.
ALTER TABLE memory_events ADD COLUMN sync_claim_token UUID;
ALTER TABLE memory_events ADD COLUMN sync_lease_until TIMESTAMPTZ;
ALTER TABLE memory_events ADD COLUMN sync_next_attempt_at TIMESTAMPTZ;

CREATE INDEX ix_memory_projection_due
    ON memory_events (sync_next_attempt_at, created_at, id)
    WHERE source = 'mem0' AND event_type = 'conversation'
      AND sync_status IN ('pending', 'failed');
CREATE INDEX ix_memory_projection_expired
    ON memory_events (sync_lease_until, updated_at, id)
    WHERE source = 'mem0' AND event_type = 'conversation' AND sync_status = 'syncing';
