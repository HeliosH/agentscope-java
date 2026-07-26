-- H2 counterpart of V28. Tenant filtering remains mandatory at the service and mapper layers.

CREATE TABLE execution_plans (
    id UUID PRIMARY KEY,
    org_id UUID NOT NULL,
    run_id UUID NOT NULL REFERENCES assistant_runs(id) ON DELETE CASCADE,
    version INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL,
    goal VARCHAR(2000) NOT NULL,
    plan_json JSON NOT NULL,
    plan_hash VARCHAR(64) NOT NULL,
    supersedes_plan_id UUID REFERENCES execution_plans(id) ON DELETE SET NULL,
    approval_required BOOLEAN NOT NULL DEFAULT TRUE,
    created_by UUID NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    decided_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT ux_execution_plans_version UNIQUE (run_id, version),
    CONSTRAINT ux_execution_plans_hash UNIQUE (run_id, plan_hash)
);
CREATE INDEX ix_execution_plans_run_created
    ON execution_plans(org_id, run_id, created_at DESC);

CREATE TABLE execution_plan_tasks (
    id UUID PRIMARY KEY,
    org_id UUID NOT NULL,
    run_id UUID NOT NULL REFERENCES assistant_runs(id) ON DELETE CASCADE,
    plan_id UUID NOT NULL REFERENCES execution_plans(id) ON DELETE CASCADE,
    task_id UUID NOT NULL REFERENCES task_nodes(id) ON DELETE CASCADE,
    client_task_id VARCHAR(128) NOT NULL,
    task_spec_hash VARCHAR(64) NOT NULL,
    CONSTRAINT ux_execution_plan_tasks_client UNIQUE (plan_id, client_task_id),
    CONSTRAINT ux_execution_plan_tasks_task UNIQUE (plan_id, task_id)
);
CREATE INDEX ix_execution_plan_tasks_run
    ON execution_plan_tasks(org_id, run_id, plan_id);

ALTER TABLE task_edges
    ADD COLUMN plan_id UUID REFERENCES execution_plans(id) ON DELETE CASCADE;
CREATE INDEX ix_task_edges_plan ON task_edges(org_id, plan_id);

ALTER TABLE run_approvals
    ADD COLUMN plan_id UUID REFERENCES execution_plans(id) ON DELETE CASCADE;
ALTER TABLE run_approvals
    ADD COLUMN idempotency_key VARCHAR(255);
CREATE UNIQUE INDEX ux_run_approvals_decision_idempotency
    ON run_approvals(org_id, run_id, idempotency_key);
