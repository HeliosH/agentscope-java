#!/usr/bin/env python3
"""Local PG/MinIO/OpenSandbox archive-only recovery with a fixture-scoped commit fault."""

import importlib.util
from pathlib import Path
import urllib.parse
import uuid


spec = importlib.util.spec_from_file_location(
    "generation_smoke", Path(__file__).with_name("session-generation-smoke.py"))
helpers = importlib.util.module_from_spec(spec)
spec.loader.exec_module(helpers)
api, confirm, is_error, pg, request, stream, wait_until = (
    helpers.api, helpers.confirm, helpers.is_error, helpers.pg,
    helpers.request, helpers.stream, helpers.wait_until)


def main(missing_bytes=False):
    assert urllib.parse.urlparse(helpers.BASE).hostname in {"localhost", "127.0.0.1"}, "Local fixture only"
    suffix = uuid.uuid4().hex
    auth = api("/api/auth/register", payload={"email": f"archive-{suffix}@e2e.test",
                                              "password": "test-" + suffix})
    token = auth["token"]
    agent = api("/api/agents", token, {"name": "WorkspaceArchiveSmoke",
                                      "sysPrompt": "Execute the requested shell command exactly.",
                                      "tools": ["execute"], "maxIters": 8}, expected=201)["id"]
    agent = str(uuid.UUID(agent))
    fixture = "archive_fault_" + suffix
    installed = False
    try:
        marker = "archive-output-" + suffix
        command = f"mkdir -p outputs && printf '%s\\n' {marker} > outputs/archive-result.txt && cat outputs/archive-result.txt"
        paused = confirm(stream(agent, token, {"message": command}))
        run = str(uuid.UUID(paused["runId"]))
        session = str(uuid.UUID(paused["sessionId"]))
        fault_table = "file_publications" if missing_bytes else "run_artifacts"
        pg(f"""CREATE FUNCTION {fixture}() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN
              IF NEW.run_id = '{run}'::uuid THEN
                RAISE EXCEPTION 'fixture archive receipt commit unavailable' USING ERRCODE = '40001';
              END IF;
              RETURN NEW;
            END $$;
            CREATE TRIGGER {fixture} BEFORE INSERT ON {fault_table}
                FOR EACH ROW EXECUTE FUNCTION {fixture}();""")
        installed = True
        events = stream(agent, token, paused)
        assert not any(is_error(event) for event in events), events[-4:]
        assert not any(event.get("type") == "RUN_FINISHED" for event in events), events[-4:]
        assert any(event.get("type") == "CUSTOM" and event.get("name") == "run_archiving"
                   for event in events), events[-4:]
        job = str(uuid.UUID(pg(f"SELECT id FROM workspace_archive_jobs WHERE run_id = '{run}'")))
        assert pg(f"SELECT status FROM task_nodes WHERE run_id = '{run}'") == "ARCHIVING"
        assert pg(f"SELECT status FROM run_attempts WHERE run_id = '{run}'") == "ARCHIVING"
        assert int(pg(f"SELECT count(*) FROM run_attempts WHERE run_id = '{run}' AND lease_expires_at IS NOT NULL")) == 0
        assert int(pg(f"SELECT count(*) FROM run_artifacts WHERE run_id = '{run}'")) == 0
        if missing_bytes:
            assert int(pg(f"SELECT count(*) FROM file_publications WHERE run_id = '{run}' AND status IN ('STORED', 'PUBLISHED')")) == 0
            assert int(pg(f"SELECT count(*) FROM workspace_archive_jobs j JOIN workspace_sources s ON s.manifest_id = j.manifest_id AND s.org_id = j.org_id AND s.user_id = j.user_id WHERE j.id = '{job}' AND s.status = 'RETAINED' AND s.deadline_at >= j.deadline_at")) == 1
            assert int(pg(f"SELECT count(*) FROM workspace_archive_items WHERE job_id = '{job}' AND publication_id IS NULL")) > 0
            assert int(pg(f"SELECT count(*) FROM sandbox_leases WHERE run_id = '{run}' AND status IN ('RESERVED', 'ACTIVE')")) == 0
            print("  OK  missing publication bytes protected by immutable escrow after sandbox release")
        model_calls = int(pg(f"SELECT count(*) FROM model_invocations WHERE run_id = '{run}'"))
        print("  OK  durable result handed over to ARCHIVING; business lease revoked")
        pg(f"DROP TRIGGER {fixture} ON {fault_table}; DROP FUNCTION {fixture}();")
        installed = False
        wait_until(lambda: api(f"/api/agents/{agent}/runs/{run}", token)["status"] == "SUCCEEDED", timeout=90)
        assert pg(f"SELECT status FROM workspace_archive_jobs WHERE id = '{job}'") == "COMPLETED"
        assert int(pg(f"SELECT count(*) FROM run_attempts WHERE run_id = '{run}'")) == 1
        assert int(pg(f"SELECT count(*) FROM model_invocations WHERE run_id = '{run}'")) == model_calls
        assert int(pg(f"SELECT count(*) FROM tool_operations WHERE run_id = '{run}' AND tool_name = 'execute' AND status = 'SUCCEEDED'")) == 1
        assert int(pg(f"SELECT count(*) FROM workspace_archive_jobs j JOIN workspace_publication_manifests m ON m.id = j.manifest_id AND m.org_id = j.org_id WHERE j.id = '{job}' AND m.status = 'READY' AND m.scan_complete AND m.file_count = (SELECT count(*) FROM workspace_manifest_entries e WHERE e.manifest_id = m.id AND e.publication_id IS NOT NULL)")) == 1
        assert int(pg(f"SELECT count(*) FROM sandbox_leases sl JOIN workspace_archive_jobs j ON j.attempt_id = sl.attempt_id AND j.org_id = sl.org_id AND j.task_id = sl.task_id AND j.run_id = sl.run_id WHERE j.id = '{job}' AND sl.workspace_snapshot_uri = 'workspace-catalog://runs/{run}/attempts/' || j.attempt_id::text || '/manifests/' || j.manifest_id::text AND sl.workspace_version IS NOT NULL")) >= 1
        print("  OK  archive recovery completes immutable receipts without tool/model replay")
        query = urllib.parse.urlencode({"path": "outputs/archive-result.txt"})
        status, body = request(f"/api/agents/{agent}/workspace/file/download?{query}", token)
        assert status == 200 and marker.encode() in body, (status, body[:100])
        assert int(pg(f"SELECT count(*) FROM chat_messages WHERE session_id = '{session}' AND role = 'assistant'")) == 1
        assert int(pg(f"SELECT count(*) FROM run_artifacts WHERE run_id = '{run}'")) >= 1
        status, raw = request(f"/api/agents/{agent}/sessions/{session}/turns/window", token)
        assert status == 200 and marker.encode() in raw, (status, raw[:100])
        print("  OK  archived file download and persisted final reply available to the web APIs")
        tables = ["workspace_archive_jobs", "workspace_archive_items"]
        if missing_bytes:
            tables += ["workspace_sources", "workspace_source_files"]
        for table in tables:
            assert pg(f"SELECT relrowsecurity AND relforcerowsecurity FROM pg_class WHERE oid = '{table}'::regclass") == "t"
            for privilege in ("SELECT", "INSERT", "UPDATE", "DELETE"):
                assert pg(f"SELECT has_table_privilege('app', '{table}', '{privilege}')") == "t"
            hidden = pg(f"SET ROLE app; SET app.current_org = '{uuid.uuid4()}'; SELECT count(*) FROM {table}")
            assert hidden.splitlines()[-1] == "0", hidden
        print("  OK  PostgreSQL FORCE RLS and application role privileges")
        if missing_bytes:
            source = str(uuid.UUID(pg(f"SELECT s.id FROM workspace_sources s JOIN workspace_archive_jobs j ON j.manifest_id = s.manifest_id AND j.org_id = s.org_id WHERE j.id = '{job}'")))
            # Only this completed fixture's escrow is accelerated; no active task or user file is touched.
            pg(f"UPDATE workspace_sources SET eligible_at = NOW() - INTERVAL '1 second' WHERE id = '{source}'")
            wait_until(lambda: pg(f"SELECT status FROM workspace_sources WHERE id = '{source}'") == "COLLECTED", timeout=90)
            status, body = request(f"/api/agents/{agent}/workspace/file/download?{query}", token)
            assert status == 200 and marker.encode() in body, (status, body[:100])
            print("  OK  real MinIO source GC completes without deleting the published user file")
        print(f"=== Workspace archive smoke: PASS={6 if missing_bytes else 4} FAIL=0 ===")
    finally:
        if installed:
            pg(f"DROP TRIGGER IF EXISTS {fixture} ON {fault_table}; DROP FUNCTION IF EXISTS {fixture}();")
        status, _ = request(f"/api/agents/{agent}", token, method="DELETE")
        assert status in (200, 204, 404), ("Agent cleanup", status)


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument("--missing-bytes", action="store_true")
    main(parser.parse_args().missing_bytes)
