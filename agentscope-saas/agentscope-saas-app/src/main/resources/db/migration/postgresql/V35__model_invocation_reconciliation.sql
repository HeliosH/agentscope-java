-- System scans span tenants, while settlement retains explicit owner predicates.
CREATE INDEX ix_model_invocations_expired ON model_invocations(deadline_at, id)
    WHERE status = 'STARTED';
