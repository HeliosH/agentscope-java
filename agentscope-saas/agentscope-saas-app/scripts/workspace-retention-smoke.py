#!/usr/bin/env python3
"""Local MinIO outage -> sealed OpenSandbox capture -> archive-only recovery."""
import concurrent.futures
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.parse
import uuid

spec = importlib.util.spec_from_file_location("generation_smoke", Path(__file__).with_name("session-generation-smoke.py"))
h = importlib.util.module_from_spec(spec)
spec.loader.exec_module(h)


def docker(*args):
    return subprocess.run(["docker", *args], check=True, capture_output=True, text=True, timeout=60).stdout.strip()


def main():
    assert urllib.parse.urlparse(h.BASE).hostname in {"localhost", "127.0.0.1"}, "Local fixture only"
    assert docker("inspect", "--format", "{{.State.Running}}", "saas-minio") == "true"
    suffix = uuid.uuid4().hex
    token = h.api("/api/auth/register", payload={"email": f"retention-{suffix}@e2e.test", "password": "test-" + suffix})["token"]
    agent = h.api("/api/agents", token, {"name": "RetentionSmoke", "tools": ["execute"],
                                       "sysPrompt": "Execute exactly the requested command.", "maxIters": 8}, expected=201)["id"]
    stopped = False
    body_fallback = os.environ.get("SANDBOX_GATE_RUNTIME_BODY_FALLBACK", "false") == "true"
    try:
        marker = "retained-output-" + suffix
        command = f"sleep 8 && mkdir -p outputs && printf '%s\\n' {marker} > outputs/retained-result.txt && cat outputs/retained-result.txt"
        if body_fallback:
            command += " && printf '%8192s' '' | tr ' ' x"
        paused = h.confirm(h.stream(agent, token, {"message": command}))
        run = str(uuid.UUID(paused["runId"]))
        session = str(uuid.UUID(paused["sessionId"]))
        with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
            pending = pool.submit(h.request, f"/api/agents/{agent}/chat/stream", token, paused, timeout=300)
            h.wait_until(lambda: int(h.pg(f"SELECT count(*) FROM tool_operations WHERE run_id = '{run}' AND tool_name = 'execute' AND status = 'RUNNING'")) == 1)
            before_outage_seq = int(h.pg(f"SELECT COALESCE(MAX(m.seq), 0) FROM runtime_messages m JOIN runtime_message_streams s ON s.id = m.stream_id WHERE s.agent_id = '{agent}' AND s.session_id = '{session}'"))
            docker("stop", "saas-minio")
            stopped = True
            status, raw = pending.result(timeout=300)
        assert status == 200, (status, raw[:200])
        events = h.parse_events(raw)
        assert not any(h.is_error(e) for e in events), events[-5:]
        assert not any(e.get("type") == "RUN_FINISHED" for e in events), events[-5:]
        assert any(e.get("name") == "run_archiving" for e in events), events[-5:]
        job = str(uuid.UUID(h.pg(f"SELECT id FROM workspace_archive_jobs WHERE run_id = '{run}'")))
        source = str(uuid.UUID(h.pg(f"SELECT p.source_id FROM workspace_provider_retentions p JOIN workspace_archive_jobs j ON j.manifest_id = p.manifest_id AND j.org_id = p.org_id WHERE j.id = '{job}'")))
        resource = str(uuid.UUID(h.pg(f"SELECT resource_id FROM workspace_provider_retentions WHERE source_id = '{source}'")))
        assert h.pg(f"SELECT status FROM workspace_provider_retentions WHERE source_id = '{source}'") == "RETAINED"
        assert h.pg(f"SELECT body_id IS NULL AND result_json IS NOT NULL FROM workspace_archive_jobs WHERE id = '{job}'") == "t"
        assert int(h.pg(f"SELECT count(*) FROM run_attempts WHERE run_id = '{run}' AND status = 'ARCHIVING' AND lease_expires_at IS NULL")) == 1
        assert int(h.pg(f"SELECT count(*) FROM sandbox_leases WHERE run_id = '{run}' AND retention_held AND status = 'RELEASED'")) == 1
        container = "sandbox-" + resource
        assert docker("inspect", "--format", "{{.State.Running}}", container) == "true"
        model_calls = int(h.pg(f"SELECT count(*) FROM model_invocations WHERE run_id = '{run}'"))
        print("  OK  whole MinIO outage seals exact output bytes and final result with a durable retention lease")
        # The ordinary release reconciler runs every 2 seconds in this local gate.
        time.sleep(6)
        assert docker("inspect", "--format", "{{.State.Running}}", container) == "true"
        assert h.api(f"/api/agents/{agent}/runs/{run}", token)["status"] == "RUNNING"
        print("  OK  business attempt releases its compute lease; ordinary reconciliation cannot destroy retained bytes")
        table = "workspace_provider_retentions"
        assert h.pg(f"SELECT relrowsecurity AND relforcerowsecurity FROM pg_class WHERE oid = '{table}'::regclass") == "t"
        hidden = h.pg(f"SET ROLE app; SET app.current_org = '{uuid.uuid4()}'; SELECT count(*) FROM {table}")
        assert hidden.splitlines()[-1] == "0"
        print("  OK  provider retention metadata has PostgreSQL FORCE RLS")
        if body_fallback:
            fallbacks = int(h.pg(f"SELECT count(*) FROM runtime_body_fallbacks WHERE agent_id = '{agent}' AND session_id = '{session}'"))
            assert fallbacks > 0, "Forced-offload result has no PG outage body"
            table = "runtime_body_fallbacks"
            assert h.pg(f"SELECT relrowsecurity AND relforcerowsecurity FROM pg_class WHERE oid = '{table}'::regclass") == "t"
            assert h.pg(f"SET ROLE app; SET app.current_org = '{uuid.uuid4()}'; SELECT count(*) FROM {table}").splitlines()[-1] == "0"
            body_ids = h.pg(f"SELECT string_agg(body_id::text, ',' ORDER BY body_id) FROM runtime_body_fallbacks WHERE agent_id = '{agent}' AND session_id = '{session}'")
            status, full = h.request(f"/api/agents/{agent}/sessions/{session}/runtime-messages?includeContent=true&afterSeq={before_outage_seq}", token)
            assert status == 200 and b"x" * 4000 in full, (status, full[:200])
            status, preview = h.request(f"/api/agents/{agent}/sessions/{session}/runtime-messages?includeContent=false&afterSeq={before_outage_seq}", token)
            assert status == 200 and b"x" * 4000 not in preview, (status, preview[:200])
            print("  OK  forced-offload body is readable during outage, previews stay bounded and FORCE RLS isolates tenants")
        docker("start", "saas-minio")
        stopped = False
        h.wait_until(lambda: h.api(f"/api/agents/{agent}/runs/{run}", token)["status"] == "SUCCEEDED", timeout=120)
        assert h.pg(f"SELECT status FROM workspace_archive_jobs WHERE id = '{job}'") == "COMPLETED"
        assert int(h.pg(f"SELECT count(*) FROM run_attempts WHERE run_id = '{run}'")) == 1
        assert int(h.pg(f"SELECT count(*) FROM tool_operations WHERE run_id = '{run}' AND tool_name = 'execute' AND status = 'SUCCEEDED'")) == 1
        assert int(h.pg(f"SELECT count(*) FROM model_invocations WHERE run_id = '{run}'")) == model_calls
        query = urllib.parse.urlencode({"path": "outputs/retained-result.txt"})
        status, body = h.request(f"/api/agents/{agent}/workspace/file/download?{query}", token)
        assert status == 200 and marker.encode() in body, (status, body[:100])
        assert int(h.pg(f"SELECT count(*) FROM chat_messages WHERE session_id = '{session}' AND role = 'assistant'")) == 1
        if body_fallback:
            h.wait_until(lambda: int(h.pg(f"SELECT count(*) FROM runtime_body_fallbacks WHERE agent_id = '{agent}' AND session_id = '{session}'")) == 0, timeout=120)
            status, full = h.request(f"/api/agents/{agent}/sessions/{session}/runtime-messages?includeContent=true", token)
            assert status == 200 and b"x" * 4000 in full, (status, full[:200])
            expected_ids = [str(uuid.UUID(value)) for value in body_ids.split(",")]
            ids_sql = ",".join(f"'{value}'" for value in expected_ids)
            assert int(h.pg(f"SELECT count(*) FROM runtime_message_bodies WHERE id IN ({ids_sql}) AND status = 'READY' AND backend = 'minio'")) == len(expected_ids)
            assert int(h.pg(f"SELECT count(*) FROM model_invocations WHERE run_id = '{run}'")) == model_calls
            print("  OK  body fallback drains to MinIO with history and invocation identities unchanged")
        print("  OK  archive consumer restores file and final reply without model/tool replay")
        h.wait_until(lambda: h.pg(f"SELECT status FROM workspace_provider_retentions WHERE source_id = '{source}'") == "CLEANED", timeout=60)
        state = subprocess.run(["docker", "inspect", "--format", "{{.State.Running}}", container], capture_output=True, text=True, timeout=10)
        assert state.returncode != 0 or state.stdout.strip() == "false"
        print("  OK  retained sandbox is deleted after immutable receipts are complete")
        print(f"=== Workspace retention smoke: PASS={7 if body_fallback else 5} FAIL=0 ===")
    finally:
        if stopped:
            docker("start", "saas-minio")
        status, _ = h.request(f"/api/agents/{agent}", token, method="DELETE")
        assert status in (200, 204, 404), ("Agent cleanup", status)


if __name__ == "__main__":
    main()
