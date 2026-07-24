-- H2 counterpart of the durable orchestration Outbox lease migration.

ALTER TABLE orchestration_outbox ADD COLUMN locked_by VARCHAR(255);
ALTER TABLE orchestration_outbox ADD COLUMN locked_until TIMESTAMP WITH TIME ZONE;
ALTER TABLE orchestration_outbox ADD COLUMN next_attempt_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE orchestration_outbox ADD COLUMN dead_lettered_at TIMESTAMP WITH TIME ZONE;

DROP INDEX ix_orchestration_outbox_pending;
CREATE INDEX ix_orchestration_outbox_pending
    ON orchestration_outbox(published_at, dead_lettered_at, next_attempt_at, locked_until, created_at);
