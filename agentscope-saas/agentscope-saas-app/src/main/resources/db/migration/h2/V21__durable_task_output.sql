-- H2 counterpart of durable task structured output.

ALTER TABLE task_nodes ADD COLUMN output_json JSON NOT NULL DEFAULT '{}';
