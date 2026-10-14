ALTER TABLE tool_operations ADD COLUMN tool_contract_hash VARCHAR(64);
ALTER TABLE tool_operations ADD COLUMN tool_contract_json JSON;
ALTER TABLE tool_operations ADD COLUMN tool_contract_recoverable BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE tool_operations ADD COLUMN logical_origin_id VARCHAR(512);
ALTER TABLE tool_operations ADD CONSTRAINT ck_tool_operation_contract_pair CHECK (
    (tool_contract_hash IS NULL AND tool_contract_json IS NULL AND NOT tool_contract_recoverable)
    OR (tool_contract_hash IS NOT NULL AND tool_contract_json IS NOT NULL));
CREATE UNIQUE INDEX ux_tool_operations_caller_call ON tool_operations(org_id,run_id,agent_run_id,logical_origin_id,tool_call_id);
