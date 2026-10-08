CREATE INDEX ix_model_invocations_expired ON model_invocations(status, deadline_at, id);
