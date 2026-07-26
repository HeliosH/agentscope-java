-- H2 counterpart of the orchestration lease reconciliation metadata.

ALTER TABLE sandbox_leases
    ADD COLUMN release_attempts INTEGER NOT NULL DEFAULT 0;

CREATE INDEX ix_sandbox_leases_reconciliation
    ON sandbox_leases(status, lease_expires_at, release_attempts);
