-- Durable multi-instance claiming and retry scheduling for orchestration event delivery.

ALTER TABLE orchestration_outbox
    ADD COLUMN locked_by VARCHAR(255),
    ADD COLUMN locked_until TIMESTAMPTZ,
    ADD COLUMN next_attempt_at TIMESTAMPTZ,
    ADD COLUMN dead_lettered_at TIMESTAMPTZ;

DROP INDEX ix_orchestration_outbox_pending;
CREATE INDEX ix_orchestration_outbox_pending
    ON orchestration_outbox(published_at, dead_lettered_at, next_attempt_at, locked_until, created_at);
