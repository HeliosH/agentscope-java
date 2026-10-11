#!/usr/bin/env python3
"""Local final-reply/status commit faults; asserts archive-only delivery, never task replay."""

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


def scenario(stage):
    suffix = uuid.uuid4().hex
    auth = api("/api/auth/register", payload={"email": f"commit-{suffix}@e2e.test",
                                              "password": "test-" + suffix})
    token = auth["token"]
    agent = str(uuid.UUID(api("/api/agents", token, {
        "name": "ResultCommitSmoke", "sysPrompt": "Execute the requested shell command exactly.",
        "tools": ["execute"], "maxIters": 8}, expected=201)["id"]))
    fixture = "commit_fault_" + suffix
    table = "chat_messages" if stage == "reply" else "run_attempts"
    installed = False
    try:
        marker = f"commit-{stage}-" + suffix
        path = f"outputs/commit-{stage}.txt"
        command = f"mkdir -p outputs && printf '%s\\n' {marker} > {path} && cat {path}"
        paused = confirm(stream(agent, token, {"message": command}))
        run, session = str(uuid.UUID(paused["runId"])), str(uuid.UUID(paused["sessionId"]))
        predicate = (f"NEW.source_run_id = '{run}'::uuid AND NEW.role = 'assistant'"
                     if stage == "reply" else f"NEW.run_id = '{run}'::uuid AND NEW.status = 'SUCCEEDED'")
        event = "INSERT" if stage == "reply" else "UPDATE"
        pg(f"""CREATE FUNCTION {fixture}() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN
              IF {predicate} THEN
                RAISE EXCEPTION 'fixture computed result submission unavailable' USING ERRCODE = '40001';
              END IF;
              RETURN NEW;
            END $$;
            CREATE TRIGGER {fixture} BEFORE {event} ON {table}
                FOR EACH ROW EXECUTE FUNCTION {fixture}();""")
        installed = True
        events = stream(agent, token, paused)
        assert not any(is_error(e) for e in events), events[-4:]
        assert not any(e.get("type") == "RUN_FINISHED" for e in events), events[-4:]
        archived = [e for e in events if e.get("type") == "CUSTOM" and e.get("name") == "run_archiving"]
        assert len(archived) == 1, events[-4:]
        job = str(uuid.UUID(archived[0]["value"]["archiveJobId"]))
        assert pg(f"SELECT status FROM run_attempts WHERE run_id = '{run}'") == "ARCHIVING"
        assert pg(f"SELECT status FROM workspace_archive_jobs WHERE id = '{job}' AND run_id = '{run}'") in {"PENDING", "RUNNING"}
        assert int(pg(f"SELECT count(*) FROM run_attempts WHERE run_id = '{run}' AND lease_expires_at IS NOT NULL")) == 0
        assert int(pg(f"SELECT count(*) FROM workspace_publication_manifests WHERE run_id = '{run}' AND status = 'READY' AND scan_complete")) == 1
        assert int(pg(f"SELECT count(*) FROM chat_messages WHERE source_run_id = '{run}'")) == (0 if stage == "reply" else 1)
        model_calls = int(pg(f"SELECT count(*) FROM model_invocations WHERE run_id = '{run}'"))
        pg(f"DROP TRIGGER {fixture} ON {table}; DROP FUNCTION {fixture}();")
        installed = False
        wait_until(lambda: api(f"/api/agents/{agent}/runs/{run}", token)["status"] == "SUCCEEDED", timeout=90)
        assert pg(f"SELECT status FROM workspace_archive_jobs WHERE id = '{job}'") == "COMPLETED"
        assert int(pg(f"SELECT count(*) FROM run_attempts WHERE run_id = '{run}'")) == 1
        assert int(pg(f"SELECT count(*) FROM model_invocations WHERE run_id = '{run}'")) == model_calls
        assert int(pg(f"SELECT count(*) FROM tool_operations WHERE run_id = '{run}' AND tool_name = 'execute' AND status = 'SUCCEEDED'")) == 1
        assert int(pg(f"SELECT count(*) FROM chat_messages WHERE source_run_id = '{run}'")) == 1
        status, body = request(f"/api/agents/{agent}/workspace/file/download?{urllib.parse.urlencode({'path': path})}", token)
        assert status == 200 and marker.encode() in body, (status, body[:100])
        status, history = request(f"/api/agents/{agent}/sessions/{session}/turns/window", token)
        assert status == 200 and marker.encode() in history, (status, history[:100])
        print(f"  OK  {stage} commit fault: archive-only delivery, one attempt/tool/reply and unchanged model calls")
    finally:
        if installed:
            pg(f"DROP TRIGGER IF EXISTS {fixture} ON {table}; DROP FUNCTION IF EXISTS {fixture}();")
        status, _ = request(f"/api/agents/{agent}", token, method="DELETE")
        assert status in (200, 204, 404), ("Agent cleanup", status)


def main():
    assert urllib.parse.urlparse(helpers.BASE).hostname in {"localhost", "127.0.0.1"}, "Local fixture only"
    for stage in ("reply", "status"):
        scenario(stage)
    print("=== Result commit smoke: PASS=2 FAIL=0 ===")


if __name__ == "__main__":
    main()
