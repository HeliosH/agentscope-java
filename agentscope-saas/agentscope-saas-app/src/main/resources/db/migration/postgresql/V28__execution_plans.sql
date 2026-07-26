-- Versioned structured plans are the scheduling source of truth. Markdown remains a projection.

CREATE TABLE execution_plans (
    id                  UUID PRIMARY KEY,
    org_id              UUID NOT NULL,
    run_id              UUID NOT NULL REFERENCES assistant_runs(id) ON DELETE CASCADE,
    version             INTEGER NOT NULL,
    status              VARCHAR(32) NOT NULL,
    goal                VARCHAR(2000) NOT NULL,
    plan_json           JSONB NOT NULL,
    plan_hash           VARCHAR(64) NOT NULL,
    supersedes_plan_id  UUID REFERENCES execution_plans(id) ON DELETE SET NULL,
    approval_required   BOOLEAN NOT NULL DEFAULT TRUE,
    created_by          UUID NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    decided_at          TIMESTAMPTZ,
    CONSTRAINT ux_execution_plans_version UNIQUE (run_id, version),
    CONSTRAINT ux_execution_plans_hash UNIQUE (run_id, plan_hash)
);
CREATE INDEX ix_execution_plans_run_created
    ON execution_plans(org_id, run_id, created_at DESC);

CREATE TABLE execution_plan_tasks (
    id              UUID PRIMARY KEY,
    org_id          UUID NOT NULL,
    run_id          UUID NOT NULL REFERENCES assistant_runs(id) ON DELETE CASCADE,
    plan_id         UUID NOT NULL REFERENCES execution_plans(id) ON DELETE CASCADE,
    task_id         UUID NOT NULL REFERENCES task_nodes(id) ON DELETE CASCADE,
    client_task_id  VARCHAR(128) NOT NULL,
    task_spec_hash  VARCHAR(64) NOT NULL,
    CONSTRAINT ux_execution_plan_tasks_client UNIQUE (plan_id, client_task_id),
    CONSTRAINT ux_execution_plan_tasks_task UNIQUE (plan_id, task_id)
);
CREATE INDEX ix_execution_plan_tasks_run
    ON execution_plan_tasks(org_id, run_id, plan_id);

ALTER TABLE task_edges
    ADD COLUMN plan_id UUID REFERENCES execution_plans(id) ON DELETE CASCADE;
CREATE INDEX ix_task_edges_plan ON task_edges(org_id, plan_id);

ALTER TABLE run_approvals
    ADD COLUMN plan_id UUID REFERENCES execution_plans(id) ON DELETE CASCADE,
    ADD COLUMN idempotency_key VARCHAR(255);
CREATE UNIQUE INDEX ux_run_approvals_decision_idempotency
    ON run_approvals(org_id, run_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

ALTER TABLE execution_plans ENABLE ROW LEVEL SECURITY;
ALTER TABLE execution_plans FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON execution_plans
    USING (org_id = current_setting('app.current_org', true)::uuid)
    WITH CHECK (org_id = current_setting('app.current_org', true)::uuid);

ALTER TABLE execution_plan_tasks ENABLE ROW LEVEL SECURITY;
ALTER TABLE execution_plan_tasks FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON execution_plan_tasks
    USING (org_id = current_setting('app.current_org', true)::uuid)
    WITH CHECK (org_id = current_setting('app.current_org', true)::uuid);
