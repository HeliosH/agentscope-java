ALTER TABLE assistant_runs ADD COLUMN execution_policy_version INTEGER CHECK (execution_policy_version = 1);
UPDATE assistant_runs r SET execution_policy_version = 1
    WHERE EXISTS (SELECT 1 FROM run_execution_policies p WHERE p.run_id = r.id AND p.org_id = r.org_id);
