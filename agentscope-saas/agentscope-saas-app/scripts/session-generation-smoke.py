#!/usr/bin/env python3
"""Local PG/OpenSandbox revocation smoke with a scripted model; not a browser/LLM quality test."""

import concurrent.futures
import json
import os
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


BASE = os.environ.get("BASE", "http://localhost:18082").rstrip("/")


def request(path, token=None, payload=None, method=None, timeout=120):
    headers = {}
    if token:
        headers["Authorization"] = "Bearer " + token
    data = None
    if payload is not None:
        data = json.dumps(payload).encode()
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as error:
        return error.code, error.read()


def api(path, token=None, payload=None, method=None, expected=200):
    status, raw = request(path, token, payload, method)
    assert status == expected, (path, status, raw[:200])
    return json.loads(raw) if raw else None


def stream(agent, token, payload):
    status, raw = request(f"/api/agents/{agent}/chat/stream", token, payload)
    assert status == 200, (status, raw[:200])
    return parse_events(raw)


def parse_events(raw):
    return [json.loads(line[5:].strip()) for line in raw.decode().splitlines()
            if line.startswith("data:") and line[5:].strip()]


def is_error(event):
    return event.get("type") == "RUN_ERROR" or (
        event.get("type") == "CUSTOM" and event.get("name") == "error")


def confirm(events):
    for event in reversed(events):
        if event.get("type") == "CUSTOM" and event.get("name") == "require_user_confirm":
            calls = event.get("value", {}).get("toolCalls", [])
            assert calls, "Confirmation has no tool calls"
            return {"sessionId": event["threadId"], "runId": event["runId"],
                    "confirmResults": [{"toolCallId": call.get("id") or call.get("toolCallId"),
                                        "toolName": call.get("name") or call.get("toolCallName"),
                                        "confirmed": True, "input": call.get("input") or {}}
                                       for call in calls]}
    raise AssertionError("Expected a paused confirmation turn")


def pg(sql):
    result = subprocess.run(["docker", "exec", "saas-pg", "psql", "-U", "agentscope",
                             "-d", "agentscope_saas_e2e", "-At", "-v", "ON_ERROR_STOP=1",
                             "-c", sql], capture_output=True, text=True, timeout=10, check=True)
    return result.stdout.strip()


def wait_until(check, timeout=60):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if check():
            return
        time.sleep(0.3)
    raise AssertionError("Timed out waiting for the expected runtime state")


def main():
    suffix = uuid.uuid4().hex
    auth = api("/api/auth/register", payload={"email": f"generation-{suffix}@e2e.test",
                                              "password": "test-" + suffix})
    token = auth["token"]
    agent = api("/api/agents", token, {"name": "SessionGenerationSmoke",
                                      "sysPrompt": "Execute the requested shell command exactly.",
                                      "tools": ["execute"], "maxIters": 8}, expected=201)["id"]
    assert str(uuid.UUID(agent)) == agent
    try:
        old = confirm(stream(agent, token, {"message": "sleep 20 && mkdir -p outputs && printf '%s\\n' obsolete-generation > outputs/obsolete-generation.txt"}))
        session, old_run = str(uuid.UUID(old["sessionId"])), str(uuid.UUID(old["runId"]))
        with concurrent.futures.ThreadPoolExecutor(max_workers=1) as executor:
            pending = executor.submit(stream, agent, token, old)
            wait_until(lambda: int(pg(f"SELECT count(*) FROM tool_operations WHERE run_id = '{old_run}' AND status = 'RUNNING' AND tool_name = 'execute'")) > 0)
            time.sleep(1)
            api(f"/api/agents/{agent}/sessions/{session}/reset", token, {}, "POST")
            retired = api(f"/api/agents/{agent}/runs/{old_run}", token)
            assert retired["status"] == "CANCELLED", retired
            assert int(pg(f"SELECT execution_generation FROM chat_sessions WHERE id = '{session}'")) == 1
            assert int(pg(f"SELECT count(*) FROM context_checkpoints WHERE run_id = '{old_run}'")) == 0
            ended = pending.result(timeout=60)
            assert any(is_error(event) for event in ended), ended[-4:]
            assert not any(event.get("type") == "RUN_FINISHED" for event in ended), ended[-4:]
        print("  OK  executing old invocation revoked and SSE terminated")

        status, raw = request(f"/api/agents/{agent}/chat/stream", token, old)
        assert status >= 400 or any(is_error(event) for event in parse_events(raw)), (status, raw[:200])
        assert int(pg(f"SELECT count(*) FROM files WHERE agent_id = '{agent}' AND logical_path = 'outputs/obsolete-generation.txt' AND status = 'active'")) == 0
        print("  OK  stale confirmation and obsolete catalog publication rejected")

        marker = "generation-fresh-" + suffix
        fresh = confirm(stream(agent, token, {"sessionId": session,
                       "message": f"mkdir -p outputs && printf '%s\\n' {marker} > outputs/generation-fresh.txt && cat outputs/generation-fresh.txt"}))
        assert fresh["sessionId"] == session
        fresh_run = str(uuid.UUID(fresh["runId"]))
        assert fresh_run != old_run
        events = stream(agent, token, fresh)
        assert marker in json.dumps(events), events[-4:]
        wait_until(lambda: api(f"/api/agents/{agent}/runs/{fresh_run}", token)["status"] == "SUCCEEDED")
        assert int(pg(f"SELECT session_generation FROM assistant_runs WHERE id = '{fresh_run}'")) == 1
        query = urllib.parse.urlencode({"path": "outputs/generation-fresh.txt"})
        status, content = request(f"/api/agents/{agent}/workspace/file/download?{query}", token)
        assert status == 200 and marker.encode() in content, (status, content[:100])
        assert int(pg(f"SELECT count(*) FROM chat_messages WHERE session_id = '{session}' AND content_json::text LIKE '%obsolete-generation%'")) == 0
        print("  OK  same logical session runs with fresh generation, output and history")

        published = int(pg(f"""SELECT count(*) FROM file_publications p
            JOIN file_versions v ON v.id = p.version_id AND v.org_id = p.org_id AND v.user_id = p.user_id
            JOIN file_publication_intents i ON i.publication_id = p.id AND i.org_id = p.org_id AND i.user_id = p.user_id
            JOIN run_attempts a ON a.id = i.attempt_id AND a.run_id = p.run_id
              AND a.task_id = i.task_id AND a.agent_run_id = i.agent_run_id AND a.org_id = p.org_id
            WHERE p.run_id = '{fresh_run}' AND p.session_generation = 1 AND p.status = 'PUBLISHED'
              AND p.logical_path = 'outputs/generation-fresh.txt' AND p.reserved_bytes = 0
              AND p.object_key = v.object_key AND p.backend = v.storage_backend
              AND p.sha256 = v.sha256 AND p.size_bytes = v.size_bytes"""))
        assert published >= 1, "Generated file has no exact publication/version receipt"
        assert int(pg(f"SELECT count(*) FROM file_publications WHERE run_id = '{fresh_run}' AND status IN ('STAGED', 'STORED')")) == 0
        print("  OK  PG publication receipt matches immutable version and reservation settled")

        api(f"/api/agents/{agent}/sessions/{session}", token, method="DELETE", expected=204)
        status, _ = request(f"/api/agents/{agent}/sessions/{session}/read", token, {}, "PATCH")
        assert status == 404, status
        assert int(pg(f"SELECT count(*) FROM chat_sessions WHERE id = '{session}'")) == 0
        print("  OK  deletion and late read marking cannot recreate the session")
        print("=== Session generation smoke: PASS=5 FAIL=0 ===")
    finally:
        status, _ = request(f"/api/agents/{agent}", token, method="DELETE")
        assert status in (200, 204, 404), ("Agent cleanup", status)


if __name__ == "__main__":
    main()
